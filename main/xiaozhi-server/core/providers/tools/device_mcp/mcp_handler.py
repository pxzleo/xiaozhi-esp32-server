"""设备端MCP客户端支持模块"""

import asyncio
import json
import re
import threading
import time
import uuid
from concurrent.futures import Future
from datetime import datetime, timezone
from typing import TYPE_CHECKING

from config.manage_api_client import (
    create_proactive_event,
    update_proactive_event_status,
    update_proactive_preference,
)
from config.logger import setup_logging
from core.handle.abortHandle import cancelActiveLLMResponse
from core.handle.sendAudioHandle import send_tts_message
from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.utils.dialogue import Message
from core.utils.auth import AuthToken
from core.utils.util import get_vision_url, sanitize_tool_name
from core.providers.tools.device_mcp.daily_briefing import build_daily_briefing
from core.providers.tools.device_mcp.proactive_policy import (
    claim_proactive_opportunity,
    set_connection_preferences,
)

if TYPE_CHECKING:
    from core.connection import ConnectionHandler

TAG = __name__
logger = setup_logging()
SCHEDULE_TRIGGERED_METHOD = "notifications/schedule/triggered"
ASSISTANT_TRIGGERED_METHOD = "notifications/assistant/triggered"
SCHEDULE_FOLLOW_UP_METHOD = "notifications/schedule/follow_up"
DEVICE_HEALTH_METHOD = "notifications/device/health"
PROACTIVE_TTS_READY_TIMEOUT_SECONDS = 2
DEVICE_REMINDER_TTS_WAIT_SECONDS = 15
_LOCAL_DATETIME_PATTERN = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$")
_INTEGER_STRING_PATTERN = re.compile(r"^\d+$")
_SIGNED_INTEGER_STRING_PATTERN = re.compile(r"^-?\d+$")
_SAFE_VERSION_PATTERN = re.compile(r"^[0-9A-Za-z._+-]{1,40}$")
_HEALTH_KINDS = {
    "network_flapping",
    "time_unsynchronized",
    "ota_update_available",
    "audio_decode_failed",
}
_HEALTH_SEVERITIES = {"info", "warning", "critical"}
_event_claim_lock = threading.Lock()


def capture_netease_briefing_resume(conn, abort_generation):
    from plugins_func.functions.play_netease_music import (
        capture_netease_briefing_resume as capture,
    )

    return capture(conn, abort_generation)


def schedule_netease_briefing_resume(
    conn, token, proactive_sentence_id, completion_event
):
    from plugins_func.functions.play_netease_music import (
        schedule_netease_briefing_resume as schedule,
    )

    return schedule(conn, token, proactive_sentence_id, completion_event)


def _device_tool_description(name: str, description: str) -> str:
    if name != "self.netease_music.logout":
        return description
    return (
        f"{description}\n"
        "当用户要求关闭或隐藏网易云登录二维码、取消本次扫码时，也必须调用本工具。"
        "本工具会取消当前扫码会话并关闭二维码；不要调整屏幕亮度，"
        "也不要在未调用本工具时声称二维码已经关闭。"
    )


class MCPClient:
    """设备端MCP客户端，用于管理MCP状态和工具"""

    def __init__(self):
        self.tools = {}  # sanitized_name -> tool_data
        self.name_mapping = {}
        self.ready = False
        self.call_results = {}  # To store Futures for tool call responses
        self.next_id = 1
        self.lock = asyncio.Lock()
        self._cached_available_tools = None  # Cache for get_available_tools

    def has_tool(self, name: str) -> bool:
        return name in self.tools

    def get_available_tools(self) -> list:
        # Check if the cache is valid
        if self._cached_available_tools is not None:
            return self._cached_available_tools

        # If cache is not valid, regenerate the list
        result = []
        for tool_name, tool_data in self.tools.items():
            function_def = {
                "name": tool_name,
                "description": tool_data["description"],
                "parameters": {
                    "type": tool_data["inputSchema"].get("type", "object"),
                    "properties": tool_data["inputSchema"].get("properties", {}),
                    "required": tool_data["inputSchema"].get("required", []),
                },
            }
            result.append({"type": "function", "function": function_def})

        self._cached_available_tools = result  # Store the generated list in cache
        return result

    async def is_ready(self) -> bool:
        async with self.lock:
            return self.ready

    async def set_ready(self, status: bool):
        async with self.lock:
            self.ready = status

    async def add_tool(self, tool_data: dict):
        async with self.lock:
            sanitized_name = sanitize_tool_name(tool_data["name"])
            normalized_tool = dict(tool_data)
            normalized_tool["description"] = _device_tool_description(
                tool_data["name"], str(tool_data.get("description") or "")
            )
            self.tools[sanitized_name] = normalized_tool
            self.name_mapping[sanitized_name] = tool_data["name"]
            self._cached_available_tools = (
                None  # Invalidate the cache when a tool is added
            )

    async def get_next_id(self) -> int:
        async with self.lock:
            current_id = self.next_id
            self.next_id += 1
            return current_id

    async def register_call_result_future(self, id: int, future: Future):
        async with self.lock:
            self.call_results[id] = future

    async def resolve_call_result(self, id: int, result: any):
        async with self.lock:
            if id in self.call_results:
                future = self.call_results.pop(id)
                if not future.done():
                    future.set_result(result)

    async def reject_call_result(self, id: int, exception: Exception):
        async with self.lock:
            if id in self.call_results:
                future = self.call_results.pop(id)
                if not future.done():
                    future.set_exception(exception)

    async def cleanup_call_result(self, id: int):
        async with self.lock:
            if id in self.call_results:
                self.call_results.pop(id)


async def send_mcp_message(conn: "ConnectionHandler", payload: dict):
    """Helper to send MCP messages, encapsulating common logic."""
    if not conn.features.get("mcp"):
        logger.bind(tag=TAG).warning("客户端不支持MCP，无法发送MCP消息")
        return

    message = json.dumps({"type": "mcp", "payload": payload})

    try:
        await conn.websocket.send(message)
        logger.bind(tag=TAG).debug(f"成功发送MCP消息: {message}")
    except Exception as e:
        logger.bind(tag=TAG).error(f"发送MCP消息失败: {e}")


async def handle_mcp_message(
    conn: "ConnectionHandler",
    mcp_client: MCPClient,
    payload: dict,
    notification_state=None,
):
    """处理MCP消息,包括初始化、工具列表和工具调用响应等"""
    if not isinstance(payload, dict):
        logger.bind(tag=TAG).error("MCP消息缺少payload字段或格式错误")
        return
    method = payload.get("method")
    if isinstance(method, str) and method.startswith("notifications/"):
        logger.bind(tag=TAG).debug("处理设备MCP通知")
    else:
        logger.bind(tag=TAG).debug(f"处理MCP消息: {str(payload)[:100]}")

    # Handle result
    if "result" in payload:
        result = payload["result"]
        msg_id = int(payload.get("id", 0))

        # Check for tool call response first
        if msg_id in mcp_client.call_results:
            logger.bind(tag=TAG).debug(
                f"收到工具调用响应，ID: {msg_id}, 结果: {result}"
            )
            await mcp_client.resolve_call_result(msg_id, result)
            return

        if msg_id == 1:  # mcpInitializeID
            logger.bind(tag=TAG).debug("收到MCP初始化响应")
            server_info = result.get("serverInfo")
            if isinstance(server_info, dict):
                name = server_info.get("name")
                version = server_info.get("version")
                logger.bind(tag=TAG).debug(
                    f"客户端MCP服务器信息: name={name}, version={version}"
                )

            await asyncio.sleep(1)
            logger.bind(tag=TAG).debug("初始化完成，开始请求MCP工具列表")
            await send_mcp_tools_list_request(conn)

            return

        elif msg_id == 2:  # mcpToolsListID
            logger.bind(tag=TAG).debug("收到MCP工具列表响应")
            if isinstance(result, dict) and "tools" in result:
                tools_data = result["tools"]
                if not isinstance(tools_data, list):
                    logger.bind(tag=TAG).error("工具列表格式错误")
                    return

                logger.bind(tag=TAG).info(
                    f"客户端设备支持的工具数量: {len(tools_data)}"
                )

                for i, tool in enumerate(tools_data):
                    if not isinstance(tool, dict):
                        continue

                    name = tool.get("name", "")
                    description = tool.get("description", "")
                    input_schema = {"type": "object", "properties": {}, "required": []}

                    if "inputSchema" in tool and isinstance(tool["inputSchema"], dict):
                        schema = tool["inputSchema"]
                        input_schema["type"] = schema.get("type", "object")
                        input_schema["properties"] = schema.get("properties", {})
                        input_schema["required"] = [
                            s for s in schema.get("required", []) if isinstance(s, str)
                        ]

                    new_tool = {
                        "name": name,
                        "description": description,
                        "inputSchema": input_schema,
                    }
                    await mcp_client.add_tool(new_tool)
                    logger.bind(tag=TAG).debug(f"客户端工具 #{i+1}: {name}")

                # 替换所有工具描述中的工具名称
                for tool_data in mcp_client.tools.values():
                    if "description" in tool_data:
                        description = tool_data["description"]
                        # 遍历所有工具名称进行替换
                        for (
                            sanitized_name,
                            original_name,
                        ) in mcp_client.name_mapping.items():
                            description = description.replace(
                                original_name, sanitized_name
                            )
                        tool_data["description"] = description

                next_cursor = result.get("nextCursor", "")
                if next_cursor:
                    logger.bind(tag=TAG).debug(f"有更多工具，nextCursor: {next_cursor}")
                    await send_mcp_tools_list_continue_request(conn, next_cursor)
                else:
                    await mcp_client.set_ready(True)
                    logger.bind(tag=TAG).debug("所有工具已获取，MCP客户端准备就绪")

                    # 刷新工具缓存，确保MCP工具被包含在函数列表中
                    if hasattr(conn, "func_handler") and conn.func_handler:
                        conn.func_handler.tool_manager.refresh_tools()
                        conn.func_handler.current_support_functions()
            return

    # Handle method calls (requests from the client)
    elif "method" in payload:
        method = payload["method"]
        if method == "notifications/netease_music/status":
            await _handle_netease_music_status_notification(
                conn, payload.get("params"), notification_state
            )
            return
        if method == SCHEDULE_TRIGGERED_METHOD:
            await _handle_schedule_triggered_notification(
                conn, payload.get("params"), notification_state
            )
            return
        if method == SCHEDULE_FOLLOW_UP_METHOD:
            await _handle_schedule_follow_up_notification(
                conn, payload.get("params"), notification_state
            )
            return
        if method == DEVICE_HEALTH_METHOD:
            await _handle_device_health_notification(
                conn, payload.get("params"), notification_state
            )
            return
        if method == ASSISTANT_TRIGGERED_METHOD:
            await _handle_assistant_triggered_notification(
                conn, payload.get("params"), notification_state
            )
            return
        if isinstance(method, str) and method.startswith("notifications/"):
            logger.bind(tag=TAG).warning("拒绝未知的设备MCP通知")
            return
        logger.bind(tag=TAG).info(f"收到MCP客户端请求: {method}")

    elif "error" in payload:
        error_data = payload["error"]
        error_msg = error_data.get("message", "未知错误")
        logger.bind(tag=TAG).error(f"收到MCP错误响应: {error_msg}")

        msg_id = int(payload.get("id", 0))
        if msg_id in mcp_client.call_results:
            await mcp_client.reject_call_result(
                msg_id, Exception(f"MCP错误: {error_msg}")
            )


async def _handle_netease_music_status_notification(
    conn, params, notification_state=None
):
    if not isinstance(params, dict):
        logger.bind(tag=TAG).warning("网易云状态通知参数格式错误")
        return
    message = params.get("message")
    if not isinstance(message, str) or not message.strip() or len(message) > 200:
        logger.bind(tag=TAG).warning("网易云状态通知消息为空或过长")
        return
    if params.get("speak") is not True:
        return

    text = message.strip()
    await _speak_proactive_notification(
        conn, text, "网易云状态", notification_state
    )


def _validate_unified_event(params, expected_topics):
    if not isinstance(params, dict):
        raise ValueError("通知参数格式错误")
    event_id = params.get("event_id")
    topic = params.get("topic")
    priority = params.get("priority")
    reason = params.get("reason")
    created_at = params.get("created_at")
    expires_at = params.get("expires_at")
    dedupe_key = params.get("dedupe_key")
    requires_response = params.get("requires_response")
    if not isinstance(event_id, str) or not event_id or len(event_id) > 96:
        raise ValueError("通知事件ID无效")
    if topic not in expected_topics:
        raise ValueError("通知主题无效")
    if priority not in ("low", "normal", "high", "critical"):
        raise ValueError("通知优先级无效")
    if not isinstance(reason, str) or not reason or len(reason) > 255:
        raise ValueError("通知原因无效")
    for name, value in (("created_at", created_at), ("expires_at", expires_at)):
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise ValueError(f"通知{name}无效")
    if created_at and expires_at <= created_at:
        raise ValueError("通知过期时间无效")
    if not isinstance(dedupe_key, str) or not dedupe_key or len(dedupe_key) > 96:
        raise ValueError("通知去重键无效")
    if not isinstance(requires_response, bool):
        raise ValueError("通知响应标记无效")
    return {
        "event_id": event_id,
        "topic": topic,
        "priority": priority,
        "reason": reason,
        "created_at": created_at,
        "expires_at": expires_at,
        "dedupe_key": dedupe_key,
        "requires_response": requires_response,
    }


def _claim_notification_event(conn, event_id):
    with _event_claim_lock:
        seen = getattr(conn, "_proactive_notification_events", None)
        if seen is None:
            seen = set()
            conn._proactive_notification_events = seen
        if event_id in seen:
            return False
        if len(seen) >= 256:
            seen.clear()
        seen.add(event_id)
        return True


def _manager_topic(topic):
    return "health" if topic in ("health", "health_critical") else "reminder"


def _iso_timestamp(value):
    return datetime.fromtimestamp(value, timezone.utc).isoformat().replace("+00:00", "Z")


async def _audit_proactive_delivery(conn, event, payload, delivered):
    mac_address = getattr(conn, "device_id", None)
    if not isinstance(mac_address, str) or not mac_address:
        logger.bind(tag=TAG).error("积极主动审计失败: 设备MAC缺失")
        return
    created_at = event["created_at"] or int(time.time())
    expires_at = event["expires_at"]
    if expires_at <= created_at:
        expires_at = created_at + 3600
    audit = {
        "mac_address": mac_address,
        "event_id": event["event_id"],
        "topic": _manager_topic(event["topic"]),
        "priority": event["priority"],
        "reason": event["reason"],
        "event_type": "reminder" if event["topic"] == "follow_up" else "system",
        "payload": payload,
        "created_at": _iso_timestamp(created_at),
        "expires_at": _iso_timestamp(expires_at),
        "dedupe_key": event["dedupe_key"],
        "requires_response": event["requires_response"],
    }
    try:
        await create_proactive_event(audit)
        await update_proactive_event_status(
            event["event_id"],
            mac_address,
            "delivered" if delivered else "failed",
            "none" if delivered else "failed",
        )
    except Exception as error:
        logger.bind(tag=TAG).error(
            f"积极主动事件审计失败: {type(error).__name__}"
        )


def _schedule_delivery_audit(conn, event, payload, delivered):
    task = asyncio.create_task(_audit_proactive_delivery(conn, event, payload, delivered))
    tasks = getattr(conn, "_proactive_audit_tasks", None)
    if tasks is None:
        tasks = set()
        conn._proactive_audit_tasks = tasks
    tasks.add(task)
    task.add_done_callback(tasks.discard)


def _schedule_visible_background_task(conn, coroutine, name):
    async def runner():
        try:
            await coroutine
        except Exception as error:
            logger.bind(tag=TAG).error(f"{name}失败: {type(error).__name__}")

    task = asyncio.create_task(runner())
    tasks = getattr(conn, "_proactive_background_tasks", None)
    if tasks is None:
        tasks = set()
        conn._proactive_background_tasks = tasks
    tasks.add(task)
    task.add_done_callback(tasks.discard)


async def _sync_preference_with_retry(conn, preference):
    mac_address = getattr(conn, "device_id", None)
    if not isinstance(mac_address, str) or not mac_address:
        raise ValueError("设备MAC缺失")
    last_error = None
    for attempt in range(2):
        try:
            await update_proactive_preference(mac_address, preference)
            return
        except Exception as error:
            last_error = error
            if attempt == 0:
                await asyncio.sleep(0)
    raise RuntimeError("偏好同步重试失败") from last_error


def handle_successful_device_tool_result(conn, actual_name, result):
    """设备权威成功结果的最小服务端同步钩子。"""
    if not isinstance(result, dict):
        return
    action = result.get("action")
    if action not in ("NONE", "RESPONSE", "REQLLM", "RECORD") or result.get("isError") is True:
        return
    if actual_name.startswith("self.proactive."):
        data = result.get("data")
        if isinstance(data, dict) and isinstance(data.get("preferences"), dict):
            data = data["preferences"]
        if not isinstance(data, dict):
            logger.bind(tag=TAG).error("设备偏好已成功但返回data无效")
            return
        fields = (
            "mode", "daily_limit", "quiet_start", "quiet_end",
            "allowed_topics", "blocked_topics",
        )
        preference = {field: data.get(field) for field in fields}
        try:
            set_connection_preferences(conn, preference)
        except ValueError as error:
            logger.bind(tag=TAG).error(f"设备偏好返回无效: {type(error).__name__}")
            return
        _schedule_visible_background_task(
            conn, _sync_preference_with_retry(conn, preference), "设备偏好同步"
        )
        return
    if actual_name == "self.schedule.create":
        data = result.get("data")
        if isinstance(data, dict) and isinstance(data.get("id"), int):
            scheduled_at = data.get("scheduled_at") or data.get("trigger_at")
            kind = data.get("kind")
            if kind in ("alarm", "reminder") and isinstance(scheduled_at, str):
                match = re.search(r"T(\d{2}:\d{2})", scheduled_at)
                if match:
                    from core.providers.tools.device_mcp.proactive_habits import (
                        schedule_habit_observation,
                    )

                    suggested_time = match.group(1)
                    schedule_habit_observation(
                        conn,
                        "schedule",
                        "time_pattern",
                        f"schedule:{kind}:{suggested_time}",
                        {
                            "description": "在固定时间安排日程",
                            "suggested_time": suggested_time,
                            "topic": "calendar",
                        },
                        # 只观察本次权威成功结果；候选在下次建连时建议，
                        # 避免与设备当前创建确认（含设备自带的重复建议）竞争 TTS。
                        allow_suggestion=False,
                    )
    schedule_action = actual_name.rsplit(".", 1)[-1]
    if actual_name.startswith("self.schedule.") and schedule_action in (
        "complete", "follow_up", "dismiss"
    ):
        event_id = getattr(conn, "_current_followup_event_id", None)
        mac_address = getattr(conn, "device_id", None)
        if isinstance(event_id, str) and isinstance(mac_address, str):
            outcome = {
                "complete": "completed",
                "follow_up": "acknowledged",
                "dismiss": "dismissed",
            }[schedule_action]
            delivery_status = "dismissed" if schedule_action == "dismiss" else "delivered"
            _schedule_visible_background_task(
                conn,
                update_proactive_event_status(
                    event_id, mac_address, delivery_status, outcome
                ),
                "完成跟进结果同步",
            )


async def _handle_schedule_follow_up_notification(conn, params, notification_state=None):
    try:
        allowed_fields = {
            "version", "event_id", "topic", "priority", "reason", "created_at",
            "expires_at", "dedupe_key", "requires_response", "follow_up",
            "source_id", "label", "speak",
        }
        if not isinstance(params, dict) or set(params) != allowed_fields:
            raise ValueError("完成跟进通知字段无效")
        event = _validate_unified_event(params, {"follow_up"})
        if params.get("version") != 1 or isinstance(params.get("version"), bool):
            raise ValueError("完成跟进通知版本无效")
        source_id = params.get("source_id")
        if not isinstance(source_id, int) or isinstance(source_id, bool) or source_id <= 0:
            raise ValueError("完成跟进来源ID无效")
        label = params.get("label")
        if not isinstance(label, str) or not label.strip() or len(label.strip()) > 80:
            raise ValueError("完成跟进标题无效")
        if params.get("follow_up") is not True or params.get("speak") is not True:
            raise ValueError("完成跟进标记无效")
        if event["requires_response"] is not True:
            raise ValueError("完成跟进必须要求响应")
    except ValueError as error:
        logger.bind(tag=TAG).warning(str(error))
        return
    if not _claim_notification_event(conn, event["event_id"]):
        logger.bind(tag=TAG).info("忽略重复的完成跟进通知")
        return
    if not claim_proactive_opportunity(
        conn,
        f"follow_up:{source_id}",
        cooldown_seconds=30 * 60,
        policy_topic="reminder",
    ):
        _schedule_delivery_audit(
            conn, event, {"title": "完成跟进", "reference_id": str(source_id), "source": "device"}, False
        )
        return
    conn._current_followup_event_id = event["event_id"]
    conn._current_followup_source_id = source_id
    sentence_id = await _speak_proactive_notification(
        conn, f"刚才提醒的{label.strip()}完成了吗？", "完成跟进", notification_state
    )
    _schedule_delivery_audit(
        conn,
        event,
        {"title": "完成跟进", "reference_id": str(source_id), "source": "device"},
        sentence_id is not None,
    )


def _validate_health_details(kind, recovered, details):
    if recovered and (details is None or details == {}):
        return {}
    if not isinstance(details, dict) or len(details) != 1:
        raise ValueError("设备健康通知详情无效")
    key, value = next(iter(details.items()))
    expected = {
        "network_flapping": "disconnects_in_5m",
        "time_unsynchronized": "uptime_seconds",
        "ota_update_available": "version",
        "audio_decode_failed": "error_code",
    }[kind]
    if key != expected or not isinstance(value, str):
        raise ValueError("设备健康通知详情无效")
    if kind == "ota_update_available":
        valid = _SAFE_VERSION_PATTERN.fullmatch(value)
    elif kind == "audio_decode_failed":
        valid = _SIGNED_INTEGER_STRING_PATTERN.fullmatch(value)
    else:
        valid = _INTEGER_STRING_PATTERN.fullmatch(value)
    if not valid:
        raise ValueError("设备健康通知详情无效")
    return details


def _health_text(kind, recovered):
    texts = {
        "network_flapping": ("设备网络连接不稳定，请检查网络。", "设备网络连接已恢复稳定。"),
        "time_unsynchronized": ("设备时间尚未同步，请检查网络和时间设置。", "设备时间已恢复同步。"),
        "ota_update_available": ("设备有可用更新，请在方便时安装。", "设备更新提示已恢复正常。"),
        "audio_decode_failed": ("设备音频解码异常，请稍后重试。", "设备音频解码已恢复正常。"),
    }
    return texts[kind][1 if recovered else 0]


async def _handle_device_health_notification(conn, params, notification_state=None):
    try:
        allowed_fields = {
            "version", "event_id", "topic", "priority", "reason", "created_at",
            "expires_at", "dedupe_key", "requires_response", "kind", "severity",
            "occurred_at", "recovered", "details",
        }
        if not isinstance(params, dict) or set(params) != allowed_fields:
            raise ValueError("设备健康通知字段无效")
        event = _validate_unified_event(params, {"health", "health_critical"})
        if params.get("version") != 1 or isinstance(params.get("version"), bool):
            raise ValueError("设备健康通知版本无效")
        kind = params.get("kind")
        severity = params.get("severity")
        recovered = params.get("recovered")
        occurred_at = params.get("occurred_at")
        if kind not in _HEALTH_KINDS or severity not in _HEALTH_SEVERITIES:
            raise ValueError("设备健康通知类型无效")
        if not isinstance(recovered, bool):
            raise ValueError("设备健康恢复标记无效")
        if not isinstance(occurred_at, int) or isinstance(occurred_at, bool) or occurred_at < 0:
            raise ValueError("设备健康发生时间无效")
        _validate_health_details(kind, recovered, params.get("details"))
        expected_priority = {"info": "normal", "warning": "high", "critical": "critical"}[severity]
        expected_topic = "health_critical" if severity == "critical" and not recovered else "health"
        if event["priority"] != expected_priority or event["topic"] != expected_topic:
            raise ValueError("设备健康策略字段不一致")
        expected_reason = "device health recovered" if recovered else "device health"
        if event["reason"] != expected_reason or event["requires_response"] is not False:
            raise ValueError("设备健康事件字段不一致")
    except ValueError as error:
        logger.bind(tag=TAG).warning(str(error))
        return
    if not _claim_notification_event(conn, event["event_id"]):
        logger.bind(tag=TAG).info("忽略重复的设备健康通知")
        return
    bypass_policy = recovered or severity == "critical"
    allowed = bypass_policy or claim_proactive_opportunity(
        conn,
        f"health:{kind}",
        cooldown_seconds=60 * 60,
        policy_topic="health",
    )
    if not allowed:
        _schedule_delivery_audit(
            conn, event, {"title": "设备健康", "reference_id": kind, "source": "device"}, False
        )
        return
    sentence_id = await _speak_proactive_notification(
        conn, _health_text(kind, recovered), "设备健康", notification_state
    )
    _schedule_delivery_audit(
        conn,
        event,
        {"title": "设备健康", "reference_id": kind, "source": "device"},
        sentence_id is not None,
    )


async def _handle_schedule_triggered_notification(
    conn, params, notification_state=None
):
    if not isinstance(params, dict):
        logger.bind(tag=TAG).warning("日程提醒通知参数格式错误")
        return

    version = params.get("version")
    schedule_id = params.get("id")
    label = params.get("label")
    triggered_at = params.get("triggered_at")
    if not isinstance(version, int) or isinstance(version, bool) or version != 1:
        logger.bind(tag=TAG).warning("日程提醒通知版本无效")
        return
    if (
        not isinstance(schedule_id, int)
        or isinstance(schedule_id, bool)
        or schedule_id <= 0
    ):
        logger.bind(tag=TAG).warning("日程提醒通知ID无效")
        return
    schedule_kind = params.get("kind")
    if schedule_kind not in ("alarm", "reminder"):
        logger.bind(tag=TAG).warning("日程提醒通知类型无效")
        return
    if not isinstance(label, str):
        logger.bind(tag=TAG).warning("日程提醒通知标题格式错误")
        return
    normalized_label = label.strip()
    if not normalized_label or len(normalized_label) > 80:
        logger.bind(tag=TAG).warning("日程提醒通知标题为空或过长")
        return
    if not isinstance(triggered_at, str) or not _LOCAL_DATETIME_PATTERN.fullmatch(
        triggered_at
    ):
        logger.bind(tag=TAG).warning("日程提醒通知触发时间格式错误")
        return
    try:
        datetime.strptime(triggered_at, "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        logger.bind(tag=TAG).warning("日程提醒通知触发时间无效")
        return
    if params.get("speak") is not True:
        logger.bind(tag=TAG).warning("日程提醒通知未要求语音播报")
        return

    if schedule_kind == "alarm":
        text = f"闹铃时间到了：{normalized_label}"
        notification_name = "闹铃"
    else:
        text = f"提醒你：{normalized_label}"
        notification_name = "日程提醒"
        completion_invited = False

        def add_completion_invitation(base_text):
            nonlocal completion_invited
            if claim_proactive_opportunity(
                conn,
                "reminder_completion",
                cooldown_seconds=30 * 60,
            ):
                completion_invited = True
                return base_text + "。处理完告诉我一声"
            return base_text

        text_transform = add_completion_invitation
    if schedule_kind == "alarm":
        text_transform = None
    sentence_id = await _speak_proactive_notification(
        conn,
        text,
        notification_name,
        notification_state,
        text_transform=text_transform,
    )
    if schedule_kind == "reminder" and completion_invited:
        from core.providers.tools.device_mcp.proactive_audit import (
            schedule_server_suggestion_audit,
        )

        schedule_server_suggestion_audit(
            conn,
            "reminder",
            "reminder completion invitation",
            reference_id=str(schedule_id),
            delivered=sentence_id is not None,
        )


async def _handle_assistant_triggered_notification(
    conn, params, notification_state=None
):
    if not isinstance(params, dict):
        logger.bind(tag=TAG).warning("主动助理通知参数格式错误")
        return
    version = params.get("version")
    schedule_id = params.get("id")
    event_id = params.get("event_id")
    triggered_at = params.get("triggered_at")
    sections = params.get("sections")
    location = params.get("location")
    if not isinstance(version, int) or isinstance(version, bool) or version != 1:
        logger.bind(tag=TAG).warning("主动助理通知版本无效")
        return
    if (
        not isinstance(schedule_id, int)
        or isinstance(schedule_id, bool)
        or schedule_id <= 0
    ):
        logger.bind(tag=TAG).warning("主动助理通知ID无效")
        return
    if params.get("workflow") != "daily_briefing":
        logger.bind(tag=TAG).warning("主动助理工作流无效")
        return
    if not isinstance(triggered_at, str) or not _LOCAL_DATETIME_PATTERN.fullmatch(
        triggered_at
    ):
        logger.bind(tag=TAG).warning("主动助理触发时间格式错误")
        return
    try:
        datetime.strptime(triggered_at, "%Y-%m-%dT%H:%M:%S")
    except ValueError:
        logger.bind(tag=TAG).warning("主动助理触发时间无效")
        return
    normalized_timestamp = triggered_at.replace("-", "").replace(":", "")
    expected_event_id = f"{schedule_id}-{normalized_timestamp}"
    if event_id != expected_event_id:
        logger.bind(tag=TAG).warning("主动助理事件ID无效")
        return
    if (
        not isinstance(sections, list)
        or not sections
        or len(sections) > 2
        or any(not isinstance(section, str) for section in sections)
        or len(set(sections)) != len(sections)
        or any(section not in ("weather", "news") for section in sections)
    ):
        logger.bind(tag=TAG).warning("主动助理模块无效")
        return
    if not isinstance(location, str) or len(location.strip()) > 40:
        logger.bind(tag=TAG).warning("主动助理地点无效")
        return
    location = location.strip()
    if "weather" in sections and not location:
        logger.bind(tag=TAG).warning("天气简报缺少地点")
        return
    if params.get("speak") is not True:
        logger.bind(tag=TAG).warning("主动助理通知未要求语音播报")
        return

    seen = getattr(conn, "_assistant_briefing_events", None)
    if seen is None:
        seen = set()
        conn._assistant_briefing_events = seen
    if event_id in seen:
        logger.bind(tag=TAG).info("忽略重复的主动助理事件")
        return
    if len(seen) >= 64:
        seen.clear()
    seen.add(event_id)
    text = await build_daily_briefing(conn, sections, location)
    abort_generation = (
        notification_state[1]
        if notification_state is not None
        else getattr(conn, "abort_generation", 0)
    )
    resume_token = capture_netease_briefing_resume(conn, abort_generation)
    completion_event = threading.Event() if resume_token is not None else None
    try:
        proactive_sentence_id = await _speak_proactive_notification(
            conn,
            text,
            "每日简报",
            notification_state,
            completion_event=completion_event,
        )
    except Exception:
        if resume_token is not None:
            completion_event.set()
            schedule_netease_briefing_resume(
                conn,
                resume_token,
                conn.sentence_id,
                completion_event,
            )
        raise
    if proactive_sentence_id is not None and isinstance(
        getattr(conn, "device_id", None), str
    ):
        from core.providers.tools.device_mcp.proactive_habits import (
            schedule_habit_observation,
        )

        schedule_habit_observation(
            conn,
            "daily_briefing",
            "time_pattern",
            f"daily_briefing:{triggered_at[11:16]}",
            {
                "description": "在固定时间收听每日简报",
                "suggested_time": triggered_at[11:16],
                "topic": "habit",
            },
            allow_suggestion=False,
        )
    if resume_token is not None:
        if proactive_sentence_id is None:
            completion_event.set()
            proactive_sentence_id = conn.sentence_id
        schedule_netease_briefing_resume(
            conn,
            resume_token,
            proactive_sentence_id,
            completion_event,
        )


async def _speak_proactive_notification(
    conn,
    text,
    notification_name,
    notification_state=None,
    completion_event=None,
    text_transform=None,
):
    if not _connection_is_active(conn):
        logger.bind(tag=TAG).info(f"{notification_name}通知因连接关闭而取消")
        return
    if notification_state is None:
        notification_state = (
            conn.sentence_id,
            getattr(conn, "abort_generation", 0),
        )
    if not _notification_state_is_current(conn, notification_state):
        logger.bind(tag=TAG).info(
            f"{notification_name}通知在处理前被新的对话轮次替代"
        )
        return

    tts = await _wait_for_proactive_tts(conn, notification_name)
    if tts is None:
        return
    if not _connection_is_active(conn):
        logger.bind(tag=TAG).info(f"{notification_name}通知在等待TTS时因连接关闭而取消")
        return
    if not _notification_state_is_current(conn, notification_state):
        logger.bind(tag=TAG).info(
            f"{notification_name}通知在等待TTS时被新的对话轮次替代"
        )
        return

    previous_sentence_id = conn.sentence_id
    sentence_id = uuid.uuid4().hex
    # 设备触发提醒前会先发 abort；该状态只属于旧轮次。
    conn.client_abort = False
    conn.sentence_id = sentence_id
    await cancelActiveLLMResponse(conn, previous_sentence_id)
    if not _connection_is_active(conn):
        logger.bind(tag=TAG).info(f"{notification_name}通知在取消旧轮次时因连接关闭而取消")
        return
    if not _proactive_sentence_is_current(conn, sentence_id, notification_state):
        logger.bind(tag=TAG).info(f"{notification_name}通知被新的对话轮次替代")
        return
    tts_control_generation = await send_tts_message(conn, "start")
    if (
        not _connection_is_active(conn)
        or not _proactive_sentence_is_current(conn, sentence_id, notification_state)
    ):
        await _stop_superseded_proactive_start(
            conn, notification_name, tts_control_generation
        )
        logger.bind(tag=TAG).info(
            f"{notification_name}通知在发送TTS开始状态时被新的对话轮次替代"
        )
        return
    conn.client_is_speaking = True
    try:
        if text_transform is not None:
            text = text_transform(text)
        tts.store_tts_text(sentence_id, text)
        tts.tts_text_queue.put(TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.FIRST,
            content_type=ContentType.ACTION,
        ))
        tts.tts_one_sentence(
            conn, ContentType.TEXT, content_detail=text, sentence_id=sentence_id
        )
        tts.tts_text_queue.put(TTSMessageDTO(
            sentence_id=sentence_id,
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
            completion_event=completion_event,
        ))
    except Exception:
        await _stop_failed_proactive_tts(
            conn, sentence_id, notification_name, tts_control_generation
        )
        raise
    conn.dialogue.put(Message(role="assistant", content=text))
    logger.bind(tag=TAG).info(f"已处理{notification_name}语音通知")
    return sentence_id


def _notification_state_is_current(conn, notification_state):
    received_sentence_id, received_abort_generation = notification_state
    return (
        conn.sentence_id == received_sentence_id
        and getattr(conn, "abort_generation", 0) == received_abort_generation
    )


def _proactive_sentence_is_current(conn, sentence_id, notification_state):
    _, received_abort_generation = notification_state
    return (
        conn.sentence_id == sentence_id
        and getattr(conn, "abort_generation", 0) == received_abort_generation
        and not conn.client_abort
    )


async def _stop_failed_proactive_tts(
    conn, sentence_id, notification_name, tts_control_generation
):
    if conn.sentence_id != sentence_id:
        return
    try:
        if _connection_is_active(conn):
            await send_tts_message(
                conn, "stop", expected_generation=tts_control_generation
            )
    except Exception as error:
        logger.bind(tag=TAG).error(
            f"{notification_name}通知失败后停止TTS异常: {type(error).__name__}"
        )
    finally:
        if (
            conn.sentence_id == sentence_id
            and getattr(conn, "tts_control_generation", 0)
            == tts_control_generation
        ):
            conn.client_is_speaking = False


async def _stop_superseded_proactive_start(
    conn, notification_name, tts_control_generation
):
    if not _connection_is_active(conn) or getattr(conn, "client_is_speaking", False):
        return
    try:
        await send_tts_message(
            conn, "stop", expected_generation=tts_control_generation
        )
    except Exception as error:
        logger.bind(tag=TAG).error(
            f"{notification_name}通知被替代后停止TTS异常: {type(error).__name__}"
        )


def _connection_is_active(conn):
    connection_state = vars(conn)
    if connection_state.get("_closed", False):
        return False
    stop_event = connection_state.get("stop_event")
    return stop_event is None or not stop_event.is_set()


async def _wait_for_proactive_tts(conn, notification_name):
    connection_state = vars(conn)
    tts_ready_event = connection_state.get("tts_ready_event")
    if tts_ready_event is None:
        tts = getattr(conn, "tts", None)
        if tts is None:
            logger.bind(tag=TAG).error(f"{notification_name}通知无法播报：TTS未初始化")
        return tts

    connection_closed_event = connection_state.get("connection_closed_event")
    if connection_closed_event is None:
        try:
            await asyncio.wait_for(
                tts_ready_event.wait(),
                timeout=PROACTIVE_TTS_READY_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            logger.bind(tag=TAG).error(
                f"{notification_name}通知等待TTS就绪超时"
            )
            return None
    else:
        ready_task = asyncio.create_task(tts_ready_event.wait())
        closed_task = asyncio.create_task(connection_closed_event.wait())
        try:
            done, _ = await asyncio.wait(
                {ready_task, closed_task},
                timeout=PROACTIVE_TTS_READY_TIMEOUT_SECONDS,
                return_when=asyncio.FIRST_COMPLETED,
            )
            if closed_task in done:
                logger.bind(tag=TAG).info(
                    f"{notification_name}通知因连接关闭而取消"
                )
                return None
            if ready_task not in done:
                logger.bind(tag=TAG).error(
                    f"{notification_name}通知等待TTS就绪超时"
                )
                return None
        finally:
            for task in (ready_task, closed_task):
                if not task.done():
                    task.cancel()
            await asyncio.gather(
                ready_task, closed_task, return_exceptions=True
            )

    tts = getattr(conn, "tts", None)
    if tts is None:
        logger.bind(tag=TAG).error(f"{notification_name}通知无法播报：TTS就绪状态无效")
        return None
    return tts


async def send_mcp_initialize_message(conn: "ConnectionHandler"):
    """发送MCP初始化消息"""

    vision_url = get_vision_url(conn.config)

    # 密钥生成token
    auth = AuthToken(conn.config["server"]["auth_key"])
    token = auth.generate_token(conn.headers.get("device-id"))

    vision = {
        "url": vision_url,
        "token": token,
    }

    payload = {
        "jsonrpc": "2.0",
        "id": 1,  # mcpInitializeID
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {
                "roots": {"listChanged": True},
                "sampling": {},
                "vision": vision,
            },
            "clientInfo": {
                "name": "XiaozhiClient",
                "version": "1.0.0",
            },
        },
    }
    logger.bind(tag=TAG).debug("发送MCP初始化消息")
    await send_mcp_message(conn, payload)


async def send_mcp_tools_list_request(conn: "ConnectionHandler"):
    """发送MCP工具列表请求"""
    payload = {
        "jsonrpc": "2.0",
        "id": 2,  # mcpToolsListID
        "method": "tools/list",
    }
    logger.bind(tag=TAG).debug("发送MCP工具列表请求")
    await send_mcp_message(conn, payload)


async def send_mcp_tools_list_continue_request(conn: "ConnectionHandler", cursor: str):
    """发送带有cursor的MCP工具列表请求"""
    payload = {
        "jsonrpc": "2.0",
        "id": 2,  # mcpToolsListID (same ID for continuation)
        "method": "tools/list",
        "params": {"cursor": cursor},
    }
    logger.bind(tag=TAG).info(f"发送带cursor的MCP工具列表请求: {cursor}")
    await send_mcp_message(conn, payload)


async def call_mcp_tool(
    conn: "ConnectionHandler",
    mcp_client: MCPClient,
    tool_name: str,
    args: str = "{}",
    timeout: int = 30,
):
    """
    调用指定的工具，并等待响应
    """
    if not await mcp_client.is_ready():
        raise RuntimeError("MCP客户端尚未准备就绪")

    if not mcp_client.has_tool(tool_name):
        raise ValueError(f"工具 {tool_name} 不存在")

    tool_call_id = await mcp_client.get_next_id()
    result_future = asyncio.Future()
    await mcp_client.register_call_result_future(tool_call_id, result_future)

    # 处理参数
    try:
        if isinstance(args, str):
            # 确保字符串是有效的JSON
            if not args.strip():
                arguments = {}
            else:
                try:
                    # 尝试直接解析
                    arguments = json.loads(args)
                except json.JSONDecodeError:
                    # 如果解析失败，尝试合并多个JSON对象
                    try:
                        # 使用正则表达式匹配所有JSON对象
                        json_objects = re.findall(r"\{[^{}]*\}", args)
                        if len(json_objects) > 1:
                            # 合并所有JSON对象
                            merged_dict = {}
                            for json_str in json_objects:
                                try:
                                    obj = json.loads(json_str)
                                    if isinstance(obj, dict):
                                        merged_dict.update(obj)
                                except json.JSONDecodeError:
                                    continue
                            if merged_dict:
                                arguments = merged_dict
                            else:
                                raise ValueError(f"无法解析任何有效的JSON对象: {args}")
                        else:
                            raise ValueError(f"参数JSON解析失败: {args}")
                    except Exception as e:
                        logger.bind(tag=TAG).error(
                            f"参数JSON解析失败: {str(e)}, 原始参数: {args}"
                        )
                        raise ValueError(f"参数JSON解析失败: {str(e)}")
        elif isinstance(args, dict):
            arguments = args
        else:
            raise ValueError(f"参数类型错误，期望字符串或字典，实际类型: {type(args)}")

        # 确保参数是字典类型
        if not isinstance(arguments, dict):
            raise ValueError(f"参数必须是字典类型，实际类型: {type(arguments)}")

    except Exception as e:
        if not isinstance(e, ValueError):
            raise ValueError(f"参数处理失败: {str(e)}")
        raise e

    actual_name = mcp_client.name_mapping.get(tool_name, tool_name)
    payload = {
        "jsonrpc": "2.0",
        "id": tool_call_id,
        "method": "tools/call",
        "params": {"name": actual_name, "arguments": arguments},
    }

    logger.bind(tag=TAG).info(f"发送客户端mcp工具调用请求: {actual_name}，参数: {args}")
    await send_mcp_message(conn, payload)

    try:
        # Wait for response or timeout
        raw_result = await asyncio.wait_for(result_future, timeout=timeout)
        logger.bind(tag=TAG).info(
            f"客户端mcp工具调用 {actual_name} 成功，原始结果: {raw_result}"
        )

        if isinstance(raw_result, dict):
            if raw_result.get("isError") is True:
                error_msg = raw_result.get(
                    "error", "工具调用返回错误，但未提供具体错误信息"
                )
                raise RuntimeError(f"工具调用错误: {error_msg}")

            content = raw_result.get("content")
            if isinstance(content, list) and len(content) > 0:
                if isinstance(content[0], dict) and "text" in content[0]:
                    # 直接返回文本内容，不进行JSON解析
                    return content[0]["text"]
        # 如果结果不是预期的格式，将其转换为字符串
        return str(raw_result)
    except asyncio.TimeoutError:
        await mcp_client.cleanup_call_result(tool_call_id)
        raise TimeoutError("工具调用请求超时")
    except Exception as e:
        await mcp_client.cleanup_call_result(tool_call_id)
        raise e
