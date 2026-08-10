import os
import base64
import json
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
    timeout = kwargs.pop("timeout", 0.5)
    return await _require_manager_client()._execute_async_request(
        method,
        endpoint,
        timeout=timeout,
        _max_retries=1,
        _retry_delay=0.1,
        **kwargs,
    )


async def get_proactive_preference(mac_address: str) -> Dict:
    """读取设备积极主动偏好。"""
    return await _execute_proactive_request(
        "GET", f"/config/proactive/preferences/{quote(mac_address, safe='')}"
    )


async def authorize_mobile_instance(
    mobile_instance_id: str,
    installation_id: str,
    token: str,
    credential_version: int,
    capabilities: list[str],
) -> Dict:
    """使用 manager-api 中的可撤销凭据账本鉴权手机 WS 握手。"""
    return await _require_manager_client()._execute_async_request(
        "POST",
        f"/config/mobile/instances/{quote(mobile_instance_id, safe='')}/authorize",
        json={
            "version": 1,
            "credential_version": credential_version,
            "installation_id": installation_id,
            "token": token,
            "capabilities": capabilities,
        },
        timeout=2.0,
        _max_retries=0,
    )


async def claim_mobile_message(mobile_instance_id: str, message_id: str) -> Dict:
    """跨重连幂等领取一条手机文字消息。"""
    data = await _require_manager_client()._execute_async_request(
        "POST",
        f"/config/mobile/instances/{quote(mobile_instance_id, safe='')}/messages/"
        f"{quote(message_id, safe='')}/claim",
        timeout=2.0,
        _max_retries=0,
    )
    return data or {"status": "revoked", "claim_token": None}


async def complete_mobile_message(
    mobile_instance_id: str, message_id: str, claim_token: str
) -> bool:
    data = await _require_manager_client()._execute_async_request(
        "POST",
        f"/config/mobile/instances/{quote(mobile_instance_id, safe='')}/messages/"
        f"{quote(message_id, safe='')}/complete",
        json={"claim_token": claim_token},
        timeout=2.0,
        _max_retries=2,
        _retry_delay=0.1,
    )
    return bool(data and data.get("completed") is True)


async def renew_mobile_message(
    mobile_instance_id: str, message_id: str, claim_token: str
) -> bool:
    data = await _require_manager_client()._execute_async_request(
        "POST",
        f"/config/mobile/instances/{quote(mobile_instance_id, safe='')}/messages/"
        f"{quote(message_id, safe='')}/renew",
        json={"claim_token": claim_token},
        timeout=2.0,
        _max_retries=1,
        _retry_delay=0.1,
    )
    return bool(data and data.get("completed") is True)


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


async def create_proactive_monitor_event(event: Dict) -> Dict:
    """按 manager-api 数据库时钟与去重账本原子创建外界事件。"""
    if not isinstance(event, dict):
        raise ValueError("外界监测事件无效")
    data = await _execute_proactive_request(
        "POST", "/config/proactive/monitor-events", json=event
    )
    if (
        not isinstance(data, dict)
        or set(data) != {
            "created", "deduped", "authoritative_event_id", "event",
            "dedupe_recorded_at",
        }
        or not isinstance(data["created"], bool)
        or not isinstance(data["deduped"], bool)
        or data["created"] == data["deduped"]
        or not isinstance(data["authoritative_event_id"], str)
        or not data["authoritative_event_id"]
        or not isinstance(data["event"], dict)
        or not isinstance(data["dedupe_recorded_at"], str)
        or not data["dedupe_recorded_at"].strip()
    ):
        raise ManageApiBusinessError("manager-api外界事件创建响应格式错误")
    return data


async def claim_proactive_monitor_tasks(lease_owner: str, limit: int = 20) -> list:
    """领取 manager-api 权威调度的到期外界监测任务。"""
    if not isinstance(lease_owner, str) or not lease_owner.strip() or len(lease_owner) > 64:
        raise ValueError("monitor lease_owner无效")
    if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 100:
        raise ValueError("monitor limit无效")
    data = await _execute_proactive_request(
        "POST",
        "/config/proactive/monitors/claim",
        json={"lease_owner": lease_owner, "limit": limit},
    )
    if not isinstance(data, list) or any(not isinstance(item, dict) for item in data):
        raise ManageApiBusinessError("manager-api监测任务响应格式错误")
    return data


async def complete_proactive_monitor_task(
    task: Dict, *, success: bool, state: Dict, error_code: Optional[str] = None
) -> None:
    """使用领取 token 完成监测任务；失效租约由 manager-api 明确拒绝。"""
    if not isinstance(task, dict) or not isinstance(state, dict):
        raise ValueError("monitor task或state无效")
    payload = {
        "device_id": task.get("device_id"),
        "monitor_type": task.get("monitor_type"),
        "lease_owner": task.get("lease_owner"),
        "lease_token": task.get("lease_token"),
        "success": success is True,
        "state": state,
    }
    if success is not True:
        if not isinstance(error_code, str) or not error_code.strip() or len(error_code) > 64:
            raise ValueError("monitor error_code无效")
        payload["error_code"] = error_code
    elif error_code is not None:
        raise ValueError("monitor成功时不能提供error_code")
    await _execute_proactive_request(
        "POST", "/config/proactive/monitors/complete", json=payload
    )


def _validate_classifier_output(output: object, candidate_count: int) -> dict:
    if not isinstance(output, str) or len(output.encode("utf-8")) > 16_384:
        raise ManageApiBusinessError("manager-api分类输出无效")
    try:
        root = json.loads(output)
    except (TypeError, ValueError) as error:
        raise ManageApiBusinessError("manager-api分类输出不是严格JSON") from error
    if not isinstance(root, dict) or set(root) != {"items"}:
        raise ManageApiBusinessError("manager-api分类输出根结构无效")
    items = root["items"]
    if not isinstance(items, list) or len(items) != candidate_count:
        raise ManageApiBusinessError("manager-api分类输出条目数量无效")
    expected = {
        "index", "is_major", "category", "severity", "confidence",
        "spoken_summary", "facts",
    }
    indexes = set()
    categories = {
        "public_safety", "natural_disaster", "major_policy",
        "international_conflict", "major_economy", "major_technology",
    }
    for item in items:
        if not isinstance(item, dict) or set(item) != expected:
            raise ManageApiBusinessError("manager-api分类条目结构无效")
        index = item["index"]
        confidence = item["confidence"]
        facts = item["facts"]
        if (
            not isinstance(index, int) or isinstance(index, bool)
            or not 0 <= index < candidate_count or index in indexes
            or not isinstance(item["is_major"], bool)
            or not isinstance(item["category"], str) or item["category"] not in categories
            or not isinstance(item["severity"], str)
            or item["severity"] not in {"low", "medium", "high", "critical"}
            or not isinstance(confidence, (int, float)) or isinstance(confidence, bool)
            or not 0 <= confidence <= 1
            or not isinstance(item["spoken_summary"], str)
            or not item["spoken_summary"].strip()
            or len(item["spoken_summary"]) > 120
            or not isinstance(facts, list) or not 1 <= len(facts) <= 8
            or any(not isinstance(fact, str) or not fact.strip() or len(fact) > 300 for fact in facts)
        ):
            raise ManageApiBusinessError("manager-api分类条目值无效")
        indexes.add(index)
    return root


async def evaluate_proactive_news_candidates(candidates: list) -> dict:
    """调用独立全局分类模型并再次校验严格七字段结果。"""
    if not isinstance(candidates, list) or not 1 <= len(candidates) <= 20:
        raise ValueError("新闻分类候选数量无效")
    try:
        data = await _execute_proactive_request(
            "POST",
            "/config/proactive/classifier/evaluate",
            json={"candidates": candidates},
            timeout=20,
        )
    except ManageApiError:
        raise
    except Exception as error:
        raise ManageApiError("manager-api新闻分类请求失败") from error
    if not isinstance(data, dict) or set(data) != {"output"}:
        raise ManageApiBusinessError("manager-api分类响应格式错误")
    return _validate_classifier_output(data["output"], len(candidates))


async def get_proactive_monitor_event(event_id: str, mac_address: str) -> Dict:
    """读取由 manager-api 持久化的权威外界事件内容。"""
    if not isinstance(event_id, str) or not event_id or len(event_id) > 64:
        raise ValueError("外界事件ID无效")
    if not isinstance(mac_address, str) or not mac_address or len(mac_address) > 50:
        raise ValueError("设备MAC无效")
    query = urlencode({"mac_address": mac_address})
    data = await _execute_proactive_request(
        "GET", f"/config/proactive/monitor-events/{quote(event_id, safe='')}?{query}"
    )
    if not isinstance(data, dict):
        raise ManageApiBusinessError("manager-api外界事件响应格式错误")
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
