import asyncio
import logging
import threading
import time
import json
import re
from urllib.parse import urlparse

import websockets
from config.logger import setup_logging


class SuppressInvalidHandshakeFilter(logging.Filter):
    """过滤掉无效握手错误日志（如HTTPS访问WS端口）"""

    def filter(self, record):
        msg = record.getMessage()
        suppress_keywords = [
            "opening handshake failed",
            "did not receive a valid HTTP request",
            "connection closed while reading HTTP request",
            "line without CRLF",
        ]
        return not any(keyword in msg for keyword in suppress_keywords)


def _setup_websockets_logger():
    """配置 websockets 相关的所有 logger，过滤无效握手错误"""
    filter_instance = SuppressInvalidHandshakeFilter()
    for logger_name in ["websockets", "websockets.server", "websockets.client"]:
        logger = logging.getLogger(logger_name)
        logger.addFilter(filter_instance)


_setup_websockets_logger()


from core.connection import ConnectionHandler
from config.config_loader import get_config_from_api_async
from core.auth import AuthManager, AuthenticationError
from core.utils.modules_initialize import initialize_modules
from core.utils.util import check_vad_update, check_asr_update
from core.mobile_protocol import MobileProtocolError, parse_capabilities
from config.manage_api_client import authorize_mobile_instance

TAG = __name__


def _replace_request_header(headers, name, value):
    """Replace a request header without leaving duplicate values behind."""
    try:
        del headers[name]
    except KeyError:
        pass
    headers[name] = value


class WebSocketServer:
    def __init__(self, config: dict):
        self.config = config
        self.logger = setup_logging(config)
        self.config_lock = asyncio.Lock()
        modules = initialize_modules(
            self.logger,
            self.config,
            "VAD" in self.config["selected_module"],
            "ASR" in self.config["selected_module"],
            "LLM" in self.config["selected_module"],
            False,
            "Memory" in self.config["selected_module"],
            "Intent" in self.config["selected_module"],
        )
        self._vad = modules["vad"] if "vad" in modules else None
        self._asr = modules["asr"] if "asr" in modules else None
        self._llm = modules["llm"] if "llm" in modules else None
        self._intent = modules["intent"] if "intent" in modules else None
        self._memory = modules["memory"] if "memory" in modules else None

        auth_config = self.config["server"].get("auth", {})
        self.auth_enable = auth_config.get("enabled", False)
        # 设备白名单
        self.allowed_devices = set(auth_config.get("allowed_devices", []))
        secret_key = self.config["server"]["auth_key"]
        expire_seconds = auth_config.get("expire_seconds", None)
        self.auth = AuthManager(secret_key=secret_key, expire_seconds=expire_seconds)
        self._initialize_model_activity_tracking()

    def _initialize_model_activity_tracking(self):
        self._model_activity_lock = threading.Lock()
        self._model_gate = threading.RLock()
        self._postprocess_lock = threading.Lock()
        self._active_model_requests = 0
        self._model_idle = threading.Event()
        self._model_idle.set()

    def mark_model_request_started(self):
        self._model_gate.acquire()
        with self._model_activity_lock:
            self._active_model_requests += 1
            self._model_idle.clear()

    def mark_model_request_finished(self):
        try:
            with self._model_activity_lock:
                self._active_model_requests = max(0, self._active_model_requests - 1)
                if self._active_model_requests == 0:
                    self._model_idle.set()
        finally:
            self._model_gate.release()

    def wait_for_idle_window(self, quiet_seconds=15):
        """等待设备连续空闲一段时间，避免总结任务与实时对话抢占模型。"""
        while True:
            self._model_idle.wait()
            time.sleep(quiet_seconds)
            if self._model_idle.is_set():
                return

    def run_postprocessing_when_idle(self, callback, quiet_seconds=15):
        """串行所有会话后处理，并在模型连续空闲后才开始。"""
        with self._postprocess_lock:
            self.wait_for_idle_window(quiet_seconds=quiet_seconds)
            # 与实时对话共用同一把门锁，消除空闲检查后的竞态窗口。
            with self._model_gate:
                return callback()

    async def start(self):
        server_config = self.config["server"]
        host = server_config.get("ip", "0.0.0.0")
        port = int(server_config.get("port", 8000))

        async with websockets.serve(
            self._handle_connection, host, port, process_request=self._http_response
        ):
            await asyncio.Future()

    async def _handle_connection(self, websocket: websockets.ServerConnection):
        is_mobile = urlparse(websocket.request.path or "").path == "/mobile/assistant"
        mobile_auth_context = None
        if is_mobile:
            try:
                mobile_auth_context = await self._prepare_mobile_connection(websocket)
            except MobileProtocolError as exception:
                await websocket.send(json.dumps({
                    "type": "error", "version": 1, "code": exception.code,
                    "message": exception.message,
                }, ensure_ascii=False))
                await websocket.close(code=4401 if exception.code == "UNAUTHORIZED" else 4400)
                return
            except Exception as exception:
                self.logger.bind(tag=TAG).error(f"手机实例鉴权服务不可用: {type(exception).__name__}")
                await websocket.send(json.dumps({
                    "type": "error", "version": 1, "code": "AUTH_SERVICE_UNAVAILABLE",
                    "message": "手机鉴权服务暂不可用",
                }, ensure_ascii=False))
                await websocket.close(code=1011)
                return

        headers = dict(websocket.request.headers)
        if not is_mobile and headers.get("device-id", None) is None:
            # 尝试从 URL 的查询参数中获取 device-id
            from urllib.parse import parse_qs

            # 从 WebSocket 请求中获取路径
            request_path = websocket.request.path
            if not request_path:
                self.logger.bind(tag=TAG).error("无法获取请求路径")
                await websocket.close()
                return
            parsed_url = urlparse(request_path)
            query_params = parse_qs(parsed_url.query)
            if "device-id" not in query_params:
                await websocket.send("端口正常，如需测试连接，请启动digital-human测试")
                await websocket.close()
                return
            else:
                websocket.request.headers["device-id"] = query_params["device-id"][0]
            if "client-id" in query_params:
                websocket.request.headers["client-id"] = query_params["client-id"][0]
            if "authorization" in query_params:
                websocket.request.headers["authorization"] = query_params[
                    "authorization"
                ][0]

        device_identity = websocket.request.headers.get("device-id", "")
        if (
            not is_mobile
            and isinstance(device_identity, str)
            and device_identity.strip().lower().startswith("mob_")
        ):
            await websocket.send(json.dumps({
                "type": "error", "version": 1, "code": "MOBILE_PATH_REQUIRED",
                "message": "手机实例必须使用手机助手连接路径",
            }, ensure_ascii=False))
            await websocket.close(code=4401)
            return

        """处理新连接，每次创建独立的ConnectionHandler"""
        # 先认证，后建立连接
        try:
            if not is_mobile:
                await self._handle_auth(websocket)
        except AuthenticationError:
            await websocket.send("认证失败")
            await websocket.close()
            return
        # 创建ConnectionHandler时传入当前server实例
        handler = ConnectionHandler(
            self.config,
            self._vad,
            self._asr,
            self._llm,
            self._memory,
            self._intent,
            self,  # 传入server实例
        )
        handler.client_kind = "mobile" if is_mobile else "device"
        if mobile_auth_context:
            handler.mobile_capabilities = set(mobile_auth_context["capabilities"])
            handler.mobile_auth_context = mobile_auth_context
        try:
            await handler.handle_connection(websocket)
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"处理连接时出错: {e}")
        finally:
            # 强制关闭连接（如果还没有关闭的话）
            try:
                # 安全地检查WebSocket状态并关闭
                if hasattr(websocket, "closed") and not websocket.closed:
                    await websocket.close()
                elif hasattr(websocket, "state") and websocket.state.name != "CLOSED":
                    await websocket.close()
                else:
                    # 如果没有closed属性，直接尝试关闭
                    await websocket.close()
            except Exception as close_error:
                self.logger.bind(tag=TAG).error(
                    f"服务器端强制关闭连接时出错: {close_error}"
                )

    async def _prepare_mobile_connection(self, websocket: websockets.ServerConnection):
        headers = dict(websocket.request.headers)
        allowed_mobile_headers = {
            "mobile-instance-id",
            "mobile-protocol-version",
            "mobile-credential-version",
            "mobile-capabilities",
        }
        if any(
            name.startswith("mobile-") and name not in allowed_mobile_headers
            for name in headers
        ):
            raise MobileProtocolError("UNKNOWN_HEADER", "手机握手包含未知字段")
        instance_id = headers.get("mobile-instance-id")
        installation_id = headers.get("client-id")
        authorization = headers.get("authorization", "")
        if "device-id" in headers:
            raise MobileProtocolError("UNKNOWN_HEADER", "手机握手不得携带硬件设备 ID")
        if not isinstance(instance_id, str) or not re.fullmatch(r"mob_[0-9a-f]{32}", instance_id):
            raise MobileProtocolError("INVALID_INSTANCE", "手机实例 ID 无效")
        if not isinstance(installation_id, str) or not re.fullmatch(
            r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}",
            installation_id,
        ):
            raise MobileProtocolError("INVALID_INSTALLATION", "手机安装实例 ID 无效")
        if headers.get("mobile-protocol-version") != "1":
            raise MobileProtocolError("UNSUPPORTED_VERSION", "不支持的手机协议版本")
        try:
            credential_version = int(headers.get("mobile-credential-version", ""))
        except ValueError as exception:
            raise MobileProtocolError("INVALID_CREDENTIAL_VERSION", "手机凭据版本无效") from exception
        if credential_version < 1:
            raise MobileProtocolError("INVALID_CREDENTIAL_VERSION", "手机凭据版本无效")
        capabilities = parse_capabilities(headers.get("mobile-capabilities"))
        if not authorization.startswith("Bearer ") or len(authorization) <= 7:
            raise MobileProtocolError("UNAUTHORIZED", "手机凭据缺失")
        result = await authorize_mobile_instance(
            instance_id, installation_id, authorization[7:], credential_version, capabilities
        )
        if (
            not result
            or result.get("authorized") is not True
            or result.get("mobile_instance_id") != instance_id
            or result.get("credential_version") != credential_version
        ):
            raise MobileProtocolError("UNAUTHORIZED", "手机凭据无效或已撤销")
        _replace_request_header(websocket.request.headers, "device-id", instance_id)
        _replace_request_header(websocket.request.headers, "client-id", installation_id)
        _replace_request_header(websocket.request.headers, "authorization", "Bearer [REDACTED]")
        return {
            "instance_id": instance_id,
            "installation_id": installation_id,
            "token": authorization[7:],
            "credential_version": credential_version,
            "capabilities": capabilities,
        }

    async def _http_response(self, websocket, request_headers):
        # 检查是否为 WebSocket 升级请求
        if request_headers.headers.get("connection", "").lower() == "upgrade":
            # 如果是 WebSocket 请求，返回 None 允许握手继续
            return None
        else:
            # 如果是普通 HTTP 请求，返回 "server is running"
            return websocket.respond(200, "Server is running\n")

    async def update_config(self) -> bool:
        """更新服务器配置并重新初始化组件

        Returns:
            bool: 更新是否成功
        """
        try:
            async with self.config_lock:
                # 重新获取配置（使用异步版本）
                new_config = await get_config_from_api_async(self.config)
                if new_config is None:
                    self.logger.bind(tag=TAG).error("获取新配置失败")
                    return False
                self.logger.bind(tag=TAG).info(f"获取新配置成功")
                # 检查 VAD 和 ASR 类型是否需要更新
                update_vad = check_vad_update(self.config, new_config)
                update_asr = check_asr_update(self.config, new_config)
                self.logger.bind(tag=TAG).info(
                    f"检查VAD和ASR类型是否需要更新: {update_vad} {update_asr}"
                )
                # 更新配置
                self.config = new_config
                # 重新初始化组件
                modules = initialize_modules(
                    self.logger,
                    new_config,
                    update_vad,
                    update_asr,
                    "LLM" in new_config["selected_module"],
                    False,
                    "Memory" in new_config["selected_module"],
                    "Intent" in new_config["selected_module"],
                )

                # 更新组件实例
                if "vad" in modules:
                    self._vad = modules["vad"]
                if "asr" in modules:
                    self._asr = modules["asr"]
                if "llm" in modules:
                    self._llm = modules["llm"]
                if "intent" in modules:
                    self._intent = modules["intent"]
                if "memory" in modules:
                    self._memory = modules["memory"]
                self.logger.bind(tag=TAG).info(f"更新配置任务执行完毕")
                return True
        except Exception as e:
            self.logger.bind(tag=TAG).error(f"更新服务器配置失败: {str(e)}")
            return False

    async def _handle_auth(self, websocket: websockets.ServerConnection):
        # 先认证，后建立连接
        if self.auth_enable:
            headers = dict(websocket.request.headers)
            device_id = headers.get("device-id", None)
            client_id = headers.get("client-id", None)
            if self.allowed_devices and device_id in self.allowed_devices:
                # 如果属于白名单内的设备，不校验token，直接放行
                return
            else:
                # 否则校验token
                token = headers.get("authorization", "")
                if token.startswith("Bearer "):
                    token = token[7:]  # 移除'Bearer '前缀
                else:
                    raise AuthenticationError("Missing or invalid Authorization header")
                # 进行认证
                auth_success = self.auth.verify_token(
                    token, client_id=client_id, username=device_id
                )
                if not auth_success:
                    raise AuthenticationError("Invalid token")
