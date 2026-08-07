import asyncio
from typing import Dict, Any, TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.handle.textMessageHandler import TextMessageHandler
from core.handle.textMessageType import TextMessageType
from core.providers.tools.device_mcp import handle_mcp_message


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
            asyncio.create_task(
                handle_mcp_message(
                    conn,
                    conn.mcp_client,
                    payload,
                    notification_state=notification_state,
                )
            )
