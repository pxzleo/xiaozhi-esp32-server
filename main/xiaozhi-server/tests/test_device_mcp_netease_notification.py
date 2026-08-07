import unittest
from unittest.mock import AsyncMock, Mock, patch

from core.providers.tools.device_mcp.mcp_handler import MCPClient, handle_mcp_message


class NeteaseMcpNotificationTest(unittest.IsolatedAsyncioTestCase):
    async def test_logout_tool_description_includes_qr_session_cancellation(self):
        client = MCPClient()

        await client.add_tool({
            "name": "self.netease_music.logout",
            "description": "Log out of NetEase Cloud Music on this device.",
            "inputSchema": {"type": "object", "properties": {}},
        })

        description = client.tools["self_netease_music_logout"]["description"]
        self.assertIn("关闭二维码", description)
        self.assertIn("取消本次扫码", description)

    async def test_speaks_valid_terminal_status_notification(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = False
        payload = {
            "jsonrpc": "2.0",
            "method": "notifications/netease_music/status",
            "params": {"message": "网易云音乐登录成功。", "speak": True},
        }

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ) as cancel:
            await handle_mcp_message(conn, Mock(), payload)

        conn.tts.store_tts_text.assert_called_once()
        notification_sentence_id, text = conn.tts.store_tts_text.call_args.args
        self.assertEqual("网易云音乐登录成功。", text)
        conn.tts.tts_one_sentence.assert_called_once()
        self.assertEqual(
            notification_sentence_id,
            conn.tts.tts_one_sentence.call_args.kwargs["sentence_id"],
        )
        cancel.assert_awaited_once_with(conn, "old-turn")
        self.assertEqual(notification_sentence_id, conn.sentence_id)
        conn.dialogue.put.assert_called_once()

    async def test_ignores_non_speaking_or_invalid_notification(self):
        conn = Mock()
        payloads = [
            {
                "method": "notifications/netease_music/status",
                "params": {"message": "无需播报", "speak": False},
            },
            {"method": "notifications/netease_music/status", "params": {}},
            {"method": "notifications/netease_music/status", "params": "invalid"},
        ]

        for payload in payloads:
            await handle_mcp_message(conn, Mock(), payload)

        conn.tts.tts_one_sentence.assert_not_called()

    async def test_notification_message_is_not_written_to_debug_log(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        secret = "MUSIC_U=must-not-enter-log"
        payload = {
            "method": "notifications/netease_music/status",
            "params": {"message": secret, "speak": False},
        }

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.logger"
        ) as test_logger:
            await handle_mcp_message(conn, Mock(), payload)

        logged_values = " ".join(str(call) for call in test_logger.mock_calls)
        self.assertNotIn(secret, logged_values)

    async def test_new_user_abort_during_cancel_prevents_stale_notification(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = False
        payload = {
            "method": "notifications/netease_music/status",
            "params": {"message": "网易云音乐登录成功。", "speak": True},
        }

        async def user_starts_while_cancelling(_conn, _old_id):
            conn.client_abort = True
            return True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(side_effect=user_starts_while_cancelling),
        ):
            await handle_mcp_message(conn, Mock(), payload)

        conn.tts.tts_one_sentence.assert_not_called()
        conn.dialogue.put.assert_not_called()


if __name__ == "__main__":
    unittest.main()
