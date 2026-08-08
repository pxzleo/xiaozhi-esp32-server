import os
import base64
from typing import Optional, Dict
from urllib.parse import quote, urlencode

import httpx

TAG = __name__


class DeviceNotFoundException(Exception):
    pass


class DeviceBindException(Exception):
    def __init__(self, bind_code):
        self.bind_code = bind_code
        super().__init__(f"设备绑定异常，绑定码: {bind_code}")


class ManageApiError(RuntimeError):
    """manager-api 请求或业务处理失败。"""


class ManageApiTimeoutError(ManageApiError):
    """manager-api 请求超时。"""


class ManageApiBusinessError(ManageApiError):
    """manager-api 返回非成功业务码。"""


class ManageApiClient:
    _instance = None
    _async_clients = {}  # 为每个事件循环存储独立的客户端
    _secret = None

    def __new__(cls, config):
        """单例模式确保全局唯一实例，并支持传入配置参数"""
        if cls._instance is None:
            cls._instance = super().__new__(cls)
            cls._init_client(config)
        return cls._instance

    @classmethod
    def _init_client(cls, config):
        """初始化配置（延迟创建客户端）"""
        cls.config = config.get("manager-api")

        if not cls.config:
            raise Exception("manager-api配置错误")

        if not cls.config.get("url") or not cls.config.get("secret"):
            raise Exception("manager-api的url或secret配置错误")

        if "你" in cls.config.get("secret"):
            raise Exception("请先配置manager-api的secret")

        cls._secret = cls.config.get("secret")
        cls.max_retries = cls.config.get("max_retries", 6)  # 最大重试次数
        cls.retry_delay = cls.config.get("retry_delay", 10)  # 初始重试延迟(秒)
        # 不在这里创建 AsyncClient，延迟到实际使用时创建
        cls._async_clients = {}

    @classmethod
    async def _ensure_async_client(cls):
        """确保异步客户端已创建（为每个事件循环创建独立的客户端）"""
        import asyncio

        try:
            loop = asyncio.get_running_loop()
            loop_id = id(loop)

            # 为每个事件循环创建独立的客户端
            if loop_id not in cls._async_clients:
                # 服务端可能主动关闭连接，httpx 连接池无法正确检测和清理
                limits = httpx.Limits(
                    max_keepalive_connections=0,  # 禁用 keep-alive，每次都新建连接
                )
                cls._async_clients[loop_id] = httpx.AsyncClient(
                    base_url=cls.config.get("url"),
                    headers={
                        "User-Agent": f"PythonClient/2.0 (PID:{os.getpid()})",
                        "Accept": "application/json",
                        "Authorization": "Bearer " + cls._secret,
                    },
                    timeout=cls.config.get("timeout", 30),
                    limits=limits,  # 使用限制
                    trust_env=False,
                )
            return cls._async_clients[loop_id]
        except RuntimeError:
            # 如果没有运行中的事件循环，创建一个临时的
            raise Exception("必须在异步上下文中调用")

    @classmethod
    async def _async_request(cls, method: str, endpoint: str, **kwargs) -> Dict:
        """发送单次异步HTTP请求并处理响应"""
        # 确保客户端已创建
        client = await cls._ensure_async_client()
        endpoint = endpoint.lstrip("/")
        response = None
        try:
            response = await client.request(method, endpoint, **kwargs)
            response.raise_for_status()

            result = response.json()

            # 处理API返回的业务错误
            if result.get("code") == 10041:
                raise DeviceNotFoundException(result.get("msg"))
            elif result.get("code") == 10042:
                raise DeviceBindException(result.get("msg"))
            elif result.get("code") != 0:
                raise ManageApiBusinessError(
                    f"manager-api业务错误: {result.get('msg', '未知错误')}"
                )

            # 返回成功数据
            return result.get("data") if result.get("code") == 0 else None
        finally:
            # 确保响应被关闭（即使异常也会执行）
            if response is not None:
                await response.aclose()

    @classmethod
    def _should_retry(cls, exception: Exception) -> bool:
        """判断异常是否应该重试"""
        # 网络连接相关错误
        if isinstance(
            exception, (httpx.ConnectError, httpx.TimeoutException, httpx.NetworkError)
        ):
            return True

        # HTTP状态码错误
        if isinstance(exception, httpx.HTTPStatusError):
            status_code = exception.response.status_code
            return status_code in [408, 429, 500, 502, 503, 504]

        return False

    @classmethod
    async def _execute_async_request(cls, method: str, endpoint: str, **kwargs) -> Dict:
        """带重试机制的异步请求执行器"""
        import asyncio

        max_retries = kwargs.pop("_max_retries", cls.max_retries)
        retry_delay = kwargs.pop("_retry_delay", cls.retry_delay)
        retry_count = 0

        while retry_count <= max_retries:
            try:
                # 执行异步请求
                return await cls._async_request(method, endpoint, **kwargs)
            except Exception as e:
                # 判断是否应该重试
                if retry_count < max_retries and cls._should_retry(e):
                    retry_count += 1
                    print(
                        f"{method} {endpoint} 异步请求失败，将在 {retry_delay:.1f} 秒后进行第 {retry_count} 次重试"
                    )
                    await asyncio.sleep(retry_delay)
                    continue
                else:
                    # 不重试，直接抛出明确的类型。
                    if isinstance(e, httpx.TimeoutException):
                        raise ManageApiTimeoutError("manager-api请求超时") from e
                    raise

    @classmethod
    def safe_close(cls):
        """安全关闭所有异步连接池"""
        import asyncio

        for client in list(cls._async_clients.values()):
            try:
                asyncio.run(client.aclose())
            except Exception:
                pass
        cls._async_clients.clear()
        cls._instance = None


def _require_manager_client() -> ManageApiClient:
    client = ManageApiClient._instance
    if client is None:
        raise ManageApiError("manager-api客户端未初始化")
    return client


async def _execute_proactive_request(method: str, endpoint: str, **kwargs):
    return await _require_manager_client()._execute_async_request(
        method,
        endpoint,
        timeout=0.5,
        _max_retries=1,
        _retry_delay=0.1,
        **kwargs,
    )


async def get_proactive_preference(mac_address: str) -> Dict:
    """读取设备积极主动偏好。"""
    return await _execute_proactive_request(
        "GET", f"/config/proactive/preferences/{quote(mac_address, safe='')}"
    )


async def update_proactive_preference(mac_address: str, preference: Dict) -> Dict:
    """同步设备端已成功更新的积极主动偏好。"""
    return await _execute_proactive_request(
        "PUT",
        f"/config/proactive/preferences/{quote(mac_address, safe='')}",
        json=preference,
    )


async def create_proactive_event(event: Dict) -> Dict:
    """幂等写入积极主动事件审计。"""
    return await _execute_proactive_request(
        "POST", "/config/proactive/events", json=event
    )


async def update_proactive_event_status(
    event_id: str,
    mac_address: str,
    delivery_status: str,
    outcome: str = "none",
    claim_token: Optional[str] = None,
) -> Dict:
    """更新事件投递状态。"""
    payload = {
        "mac_address": mac_address,
        "delivery_status": delivery_status,
        "outcome": outcome,
    }
    if claim_token is not None:
        payload["claim_token"] = claim_token
    return await _execute_proactive_request(
        "PUT",
        f"/config/proactive/events/{quote(event_id, safe='')}/status",
        json=payload,
    )


async def claim_proactive_event(
    event_id: str, mac_address: str, claim_token: str
) -> bool:
    """原子领取待投递事件；仅一个并发连接可成功。"""
    claimed = await _execute_proactive_request(
        "POST",
        f"/config/proactive/events/{quote(event_id, safe='')}/claim",
        json={"mac_address": mac_address, "claim_token": claim_token},
    )
    if not isinstance(claimed, bool):
        raise ManageApiError("manager-api主动事件领取响应无效")
    return claimed


async def observe_proactive_habit(observation: Dict) -> Dict:
    """提交一次受控的习惯证据。"""
    return await _execute_proactive_request(
        "POST", "/config/proactive/habits/observe", json=observation
    )


async def get_proactive_habit_candidates(mac_address: str) -> list:
    """读取已达阈值且尚未处理的习惯候选。"""
    query = urlencode({"mac_address": mac_address})
    data = await _execute_proactive_request(
        "GET", f"/config/proactive/habits/candidates?{query}"
    )
    if not isinstance(data, list):
        raise ManageApiBusinessError("manager-api习惯候选响应格式错误")
    return data

async def get_server_config() -> Optional[Dict]:
    """获取服务器基础配置"""
    return await ManageApiClient._instance._execute_async_request(
        "POST", "/config/server-base"
    )


async def get_agent_models(
    mac_address: str, client_id: str, selected_module: Dict
) -> Optional[Dict]:
    """获取代理模型配置"""
    return await ManageApiClient._instance._execute_async_request(
        "POST",
        "/config/agent-models",
        json={
            "macAddress": mac_address,
            "clientId": client_id,
            "selectedModule": selected_module,
        },
    )


async def get_correct_words(mac_address: str) -> Optional[Dict]:
    """获取智能体替换词"""
    try:
        return await ManageApiClient._instance._execute_async_request(
            "POST", "/config/correct-words",
            json={"macAddress": mac_address}
        )
    except Exception as e:
        print(f"获取替换词失败: {e}")
        return None


async def generate_and_save_chat_summary(session_id: str) -> Optional[Dict]:
    """生成并保存聊天记录总结"""
    try:
        return await ManageApiClient._instance._execute_async_request(
            "POST",
            f"/agent/chat-summary/{session_id}/save",
            timeout=120,
        )
    except Exception as e:
        print(f"生成并保存聊天记录总结失败: {e}")
        return None


async def generate_and_save_chat_title(session_id: str) -> Optional[Dict]:
    """生成并保存聊天标题"""
    try:
        return await ManageApiClient._instance._execute_async_request(
            "POST",
            f"/agent/chat-title/{session_id}/generate",
        )
    except Exception as e:
        print(f"生成并保存聊天标题失败: {e}")
        return None


async def report(
    mac_address: str, session_id: str, chat_type: int, content: str, audio, report_time
) -> Optional[Dict]:
    """异步聊天记录上报"""
    if not content or not ManageApiClient._instance:
        return None
    try:
        return await ManageApiClient._instance._execute_async_request(
            "POST",
            f"/agent/chat-history/report",
            json={
                "macAddress": mac_address,
                "sessionId": session_id,
                "chatType": chat_type,
                "content": content,
                "reportTime": report_time,
                "audioBase64": (
                    base64.b64encode(audio).decode("utf-8") if audio else None
                ),
            },
        )
    except Exception as e:
        print(f"TTS上报失败: {e}")
        return None


async def lookup_address_book(caller_mac: str, nickname: str) -> Optional[Dict]:
    """根据昵称查找目标设备"""
    if not ManageApiClient._instance:
        return None
    try:
        return await ManageApiClient._instance._execute_async_request(
            "GET",
            f"/device/address-book/lookup?callerMac={caller_mac}&nickname={nickname}",
        )
    except Exception as e:
        print(f"通讯录查找失败: {e}")
        return None


def init_service(config):
    ManageApiClient(config)


def manage_api_http_safe_close():
    ManageApiClient.safe_close()
