import asyncio
from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType
from core.providers.tools.device_mcp import handle_mcp_message

TAG = __name__


async def _handle_mcp_message_background(
    conn, payload, notification_state
) -> None:
    try:
        await handle_mcp_message(
            conn,
            conn.mcp_client,
            payload,
            notification_state=notification_state,
        )
    except asyncio.CancelledError:
        raise
    except Exception as error:
        conn.logger.bind(tag=TAG).error(
            f"MCP后台消息处理失败: {type(error).__name__}"
        )


class McpTextMessageHandler(TextMessageHandler):
    """MCP消息处理器"""

    @property
    def message_type(self) -> TextMessageType:
        return TextMessageType.MCP

    async def handle(self, conn: "ConnectionHandler", msg_json: Dict[str, Any]) -> None:
        if "payload" in msg_json:
            payload = msg_json["payload"]
            method = payload.get("method") if isinstance(payload, dict) else None
            notification_state = None
            if isinstance(method, str) and method.startswith("notifications/"):
                notification_state = (
                    conn.sentence_id,
                    getattr(conn, "abort_generation", 0),
                )
            task = asyncio.create_task(
                _handle_mcp_message_background(
                    conn,
                    payload,
                    notification_state,
                )
            )
            if notification_state is not None:
                tasks = getattr(conn, "_proactive_background_tasks", None)
                if tasks is None:
                    tasks = set()
                    conn._proactive_background_tasks = tasks
                tasks.add(task)
                task.add_done_callback(tasks.discard)
