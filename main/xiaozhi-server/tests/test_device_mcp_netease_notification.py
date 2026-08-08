import asyncio
import json
import threading
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from core.handle import receiveAudioHandle
from core.connection import ConnectionHandler
from core.handle.sendAudioHandle import sendAudioMessage, send_tts_message
from core.handle.textHandler.mcpMessageHandler import McpTextMessageHandler
from core.providers.tts.dto.dto import SentenceType
from core.providers.tools.device_mcp.mcp_executor import DeviceMCPExecutor
from core.providers.tools.device_mcp.mcp_handler import MCPClient, handle_mcp_message
from plugins_func.register import Action


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
        conn.tts_control_generation = 0
        conn.session_id = "netease-session"
        conn.websocket.send = AsyncMock()
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
        conn.tts_control_generation = 0
        conn.session_id = "reminder-session"
        conn.websocket.send = AsyncMock()
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


class ScheduleMcpNotificationTest(unittest.IsolatedAsyncioTestCase):
    @staticmethod
    def _valid_payload(**overrides):
        params = {
            "version": 1,
            "id": 7,
            "kind": "reminder",
            "label": "喝水",
            "triggered_at": "2026-08-07T14:30:00",
            "speak": True,
        }
        params.update(overrides)
        return {
            "jsonrpc": "2.0",
            "method": "notifications/schedule/triggered",
            "params": params,
        }

    async def test_valid_reminder_speaks_exact_text_without_llm(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = False
        conn.tts_control_generation = 0
        conn.session_id = "reminder-session"
        conn.websocket.send = AsyncMock()

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ) as cancel, patch(
            "core.providers.tools.device_mcp.mcp_handler.claim_proactive_opportunity",
            return_value=True,
        ):
            await handle_mcp_message(conn, Mock(), self._valid_payload(label="  喝水  "))

        sentence_id, text = conn.tts.store_tts_text.call_args.args
        self.assertEqual("提醒你：喝水。处理完告诉我一声", text)
        self.assertEqual(sentence_id, conn.sentence_id)
        self.assertEqual(
            sentence_id,
            conn.tts.tts_one_sentence.call_args.kwargs["sentence_id"],
        )
        self.assertEqual(
            "提醒你：喝水。处理完告诉我一声",
            conn.tts.tts_one_sentence.call_args.kwargs["content_detail"],
        )
        self.assertEqual(2, conn.tts.tts_text_queue.put.call_count)
        first, last = [call.args[0] for call in conn.tts.tts_text_queue.put.call_args_list]
        self.assertEqual("FIRST", first.sentence_type.name)
        self.assertEqual("LAST", last.sentence_type.name)
        cancel.assert_awaited_once_with(conn, "old-turn")
        conn.dialogue.put.assert_called_once()
        conn.llm.assert_not_called()

    async def test_valid_alarm_speaks_exact_text_without_llm(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = False
        conn.tts_control_generation = 0
        conn.session_id = "alarm-session"
        conn.websocket.send = AsyncMock()

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ):
            await handle_mcp_message(
                conn,
                Mock(),
                self._valid_payload(kind="alarm", label="  起床  "),
            )

        _, text = conn.tts.store_tts_text.call_args.args
        self.assertEqual("闹铃时间到了：起床", text)
        conn.tts.tts_one_sentence.assert_called_once()
        conn.dialogue.put.assert_called_once()

    async def test_alarm_websocket_order_starts_before_text_audio_and_stop(self):
        tts = SimpleNamespace(
            store_tts_text=Mock(),
            tts_text_queue=Mock(),
            tts_one_sentence=Mock(),
            tts_audio_first_sentence=False,
        )
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            client_is_speaking=False,
            tts=tts,
            websocket=SimpleNamespace(send=AsyncMock()),
            session_id="alarm-session",
            dialogue=Mock(),
            config={"tts_audio_send_delay": -1},
            recent_tts_texts=[],
            calling=False,
            close_after_chat=False,
            conn_from_mqtt_gateway=False,
            clearSpeakStatus=Mock(),
            logger=Mock(),
        )
        conn.clearSpeakStatus = Mock(
            side_effect=lambda: setattr(conn, "client_is_speaking", False)
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ), patch(
            "core.handle.sendAudioHandle._wait_for_audio_completion",
            new=AsyncMock(),
        ):
            await handle_mcp_message(
                conn,
                Mock(),
                self._valid_payload(kind="alarm", label="起床"),
            )
            self.assertTrue(conn.client_is_speaking)
            await sendAudioMessage(
                conn,
                SentenceType.FIRST,
                [b"opus-frame"],
                "闹铃时间到了：起床",
                sentence_id=conn.sentence_id,
            )
            await sendAudioMessage(
                conn,
                SentenceType.LAST,
                [],
                None,
                sentence_id=conn.sentence_id,
            )

        sent = [call.args[0] for call in conn.websocket.send.await_args_list]
        self.assertEqual("start", json.loads(sent[0])["state"])
        self.assertEqual("sentence_start", json.loads(sent[1])["state"])
        self.assertEqual(b"opus-frame", sent[2])
        self.assertEqual("stop", json.loads(sent[3])["state"])
        self.assertFalse(conn.client_is_speaking)

    async def test_abort_during_start_send_prevents_proactive_tts_enqueue(self):
        tts = SimpleNamespace(
            store_tts_text=Mock(),
            tts_text_queue=Mock(),
            tts_one_sentence=Mock(),
        )
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            client_is_speaking=False,
            tts=tts,
            session_id="abort-during-start",
            dialogue=Mock(),
            clearSpeakStatus=Mock(),
            config={},
        )

        async def abort_while_sending_start(_message):
            conn.abort_generation += 1
            conn.client_abort = True

        conn.websocket = SimpleNamespace(
            send=AsyncMock(side_effect=abort_while_sending_start)
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ):
            await handle_mcp_message(
                conn,
                Mock(),
                self._valid_payload(kind="alarm", label="起床"),
            )

        self.assertFalse(conn.client_is_speaking)
        tts.store_tts_text.assert_not_called()
        tts.tts_one_sentence.assert_not_called()
        states = [
            json.loads(call.args[0])["state"]
            for call in conn.websocket.send.await_args_list
        ]
        self.assertEqual(["start", "stop"], states)

    async def test_enqueue_failure_after_start_sends_stop_and_clears_state(self):
        tts = SimpleNamespace(
            store_tts_text=Mock(side_effect=RuntimeError("queue failed")),
            tts_text_queue=Mock(),
            tts_one_sentence=Mock(),
        )
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            client_is_speaking=False,
            tts=tts,
            websocket=SimpleNamespace(send=AsyncMock()),
            session_id="enqueue-failure",
            dialogue=Mock(),
            config={},
        )
        conn.clearSpeakStatus = Mock(
            side_effect=lambda: setattr(conn, "client_is_speaking", False)
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ):
            with self.assertRaisesRegex(RuntimeError, "queue failed"):
                await handle_mcp_message(
                    conn,
                    Mock(),
                    self._valid_payload(kind="alarm", label="起床"),
                )

        states = [
            json.loads(call.args[0])["state"]
            for call in conn.websocket.send.await_args_list
        ]
        self.assertEqual(["start", "stop"], states)
        self.assertFalse(conn.client_is_speaking)

    async def test_superseded_cleanup_does_not_stop_new_tts_owner(self):
        tts = SimpleNamespace(
            store_tts_text=Mock(),
            tts_text_queue=Mock(),
            tts_one_sentence=Mock(),
        )
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            client_is_speaking=False,
            tts=tts,
            session_id="generation-race",
            dialogue=Mock(),
            config={},
            clearSpeakStatus=Mock(),
        )
        send_count = 0

        async def replace_during_old_start(_message):
            nonlocal send_count
            send_count += 1
            if send_count == 1:
                conn.sentence_id = "new-user-turn"
                conn.abort_generation += 1
                conn.client_abort = True

        conn.websocket = SimpleNamespace(
            send=AsyncMock(side_effect=replace_during_old_start)
        )

        async def new_owner_starts_while_old_stop_waits(_conn):
            conn.client_abort = False
            await send_tts_message(conn, "start")
            conn.client_is_speaking = True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ), patch(
            "core.handle.sendAudioHandle._wait_for_audio_completion",
            new=AsyncMock(side_effect=new_owner_starts_while_old_stop_waits),
        ):
            await handle_mcp_message(
                conn,
                Mock(),
                self._valid_payload(kind="alarm", label="起床"),
            )

        states = [
            json.loads(call.args[0])["state"]
            for call in conn.websocket.send.await_args_list
        ]
        self.assertEqual(["start", "start"], states)
        self.assertTrue(conn.client_is_speaking)
        tts.tts_one_sentence.assert_not_called()

    async def test_failure_cleanup_does_not_clear_same_sentence_new_owner(self):
        tts = SimpleNamespace(
            store_tts_text=Mock(side_effect=RuntimeError("queue failed")),
            tts_text_queue=Mock(),
            tts_one_sentence=Mock(),
        )
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            client_is_speaking=False,
            tts=tts,
            websocket=SimpleNamespace(send=AsyncMock()),
            session_id="same-sentence-generation-race",
            dialogue=Mock(),
            config={},
            clearSpeakStatus=Mock(),
        )

        async def same_sentence_new_owner_starts(_conn):
            await send_tts_message(conn, "start")
            conn.client_is_speaking = True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ), patch(
            "core.handle.sendAudioHandle._wait_for_audio_completion",
            new=AsyncMock(side_effect=same_sentence_new_owner_starts),
        ):
            with self.assertRaisesRegex(RuntimeError, "queue failed"):
                await handle_mcp_message(
                    conn,
                    Mock(),
                    self._valid_payload(kind="alarm", label="起床"),
                )

        states = [
            json.loads(call.args[0])["state"]
            for call in conn.websocket.send.await_args_list
        ]
        self.assertEqual(["start", "start"], states)
        self.assertTrue(conn.client_is_speaking)

    async def test_reconnected_reminder_waits_for_delayed_tts_initialization(self):
        tts_ready = asyncio.Event()
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            tts=None,
            tts_ready_event=tts_ready,
            dialogue=Mock(),
            session_id="reconnect-session",
            websocket=SimpleNamespace(send=AsyncMock()),
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ):
            notification = asyncio.create_task(
                handle_mcp_message(conn, Mock(), self._valid_payload())
            )
            await asyncio.sleep(0)
            self.assertFalse(notification.done())
            conn.tts = Mock()
            tts_ready.set()
            await notification

        conn.tts.tts_one_sentence.assert_called_once()
        conn.dialogue.put.assert_called_once()

    async def test_new_turn_wins_while_reminder_waits_for_tts(self):
        tts_ready = asyncio.Event()
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            tts=None,
            tts_ready_event=tts_ready,
            dialogue=Mock(),
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ) as cancel:
            notification = asyncio.create_task(
                handle_mcp_message(conn, Mock(), self._valid_payload())
            )
            await asyncio.sleep(0)
            conn.sentence_id = "new-user-turn"
            conn.tts = Mock()
            tts_ready.set()
            await notification

        cancel.assert_not_awaited()
        conn.tts.tts_one_sentence.assert_not_called()
        conn.dialogue.put.assert_not_called()

    async def test_reminder_tts_ready_timeout_is_logged_without_speaking(self):
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            tts=None,
            tts_ready_event=asyncio.Event(),
            dialogue=Mock(),
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.PROACTIVE_TTS_READY_TIMEOUT_SECONDS",
            0.01,
            create=True,
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.logger"
        ) as test_logger:
            await handle_mcp_message(conn, Mock(), self._valid_payload())

        self.assertEqual("old-turn", conn.sentence_id)
        conn.dialogue.put.assert_not_called()
        logged_values = " ".join(str(call) for call in test_logger.mock_calls)
        self.assertIn("TTS", logged_values)
        self.assertIn("超时", logged_values)

    async def test_reminder_ready_wait_fits_device_delivery_window(self):
        from core.handle.abortHandle import LLM_CANCEL_TIMEOUT_SECONDS
        from core.providers.tts.index_stream import INDEX_STREAM_REQUEST_TIMEOUT_SECONDS
        from core.providers.tools.device_mcp import mcp_handler

        self.assertEqual(2, mcp_handler.PROACTIVE_TTS_READY_TIMEOUT_SECONDS)
        self.assertLess(
            mcp_handler.PROACTIVE_TTS_READY_TIMEOUT_SECONDS
            + LLM_CANCEL_TIMEOUT_SECONDS
            + INDEX_STREAM_REQUEST_TIMEOUT_SECONDS,
            mcp_handler.DEVICE_REMINDER_TTS_WAIT_SECONDS,
        )

    async def test_connection_close_while_waiting_for_tts_discards_reminder(self):
        tts_ready = asyncio.Event()
        stop_event = threading.Event()
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            tts=None,
            tts_ready_event=tts_ready,
            dialogue=Mock(),
            stop_event=stop_event,
            _closed=False,
            connection_closed_event=asyncio.Event(),
        )

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.logger"
        ) as test_logger:
            notification = asyncio.create_task(
                handle_mcp_message(conn, Mock(), self._valid_payload())
            )
            await asyncio.sleep(0)
            stop_event.set()
            conn._closed = True
            conn.connection_closed_event.set()
            await asyncio.wait_for(notification, timeout=0.1)

        conn.dialogue.put.assert_not_called()
        logged_values = " ".join(str(call) for call in test_logger.mock_calls)
        self.assertIn("连接关闭", logged_values)
        self.assertNotIn("超时", logged_values)

    async def test_connection_close_during_old_turn_cancel_discards_reminder(self):
        stop_event = threading.Event()
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            client_abort=False,
            tts=Mock(),
            dialogue=Mock(),
            stop_event=stop_event,
            _closed=False,
        )

        async def close_during_cancel(_conn, _old_id):
            stop_event.set()
            conn._closed = True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(side_effect=close_during_cancel),
        ):
            await handle_mcp_message(conn, Mock(), self._valid_payload())

        conn.tts.tts_one_sentence.assert_not_called()
        conn.dialogue.put.assert_not_called()

    async def test_tts_ready_event_is_set_after_audio_channels_open(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.tts = Mock()
        conn.tts.open_audio_channels = AsyncMock()
        conn.tts_ready_event = asyncio.Event()
        conn.connection_closed_event = asyncio.Event()
        conn.stop_event = threading.Event()

        await conn._open_tts_channels()

        conn.tts.open_audio_channels.assert_awaited_once_with(conn)
        self.assertTrue(conn.tts_ready_event.is_set())

    async def test_rejects_every_invalid_contract_field(self):
        invalid_payloads = [
            {"method": "notifications/schedule/triggered", "params": "invalid"},
            self._valid_payload(version=None),
            self._valid_payload(version=True),
            self._valid_payload(version=1.0),
            self._valid_payload(version=2),
            self._valid_payload(id=None),
            self._valid_payload(id=True),
            self._valid_payload(id=1.0),
            self._valid_payload(id=0),
            self._valid_payload(id=-1),
            self._valid_payload(kind="timer"),
            self._valid_payload(label=None),
            self._valid_payload(label="   "),
            self._valid_payload(label="醒" * 81),
            self._valid_payload(triggered_at=None),
            self._valid_payload(triggered_at="2026-8-07T14:30:00"),
            self._valid_payload(triggered_at="2026-02-30T14:30:00"),
            self._valid_payload(speak=False),
            self._valid_payload(speak=1),
        ]
        conn = Mock()

        for payload in invalid_payloads:
            await handle_mcp_message(conn, Mock(), payload)

        conn.tts.tts_one_sentence.assert_not_called()
        conn.dialogue.put.assert_not_called()

    async def test_label_is_never_written_to_debug_or_error_log(self):
        secret = "PRIVATE_REMINDER_BODY"
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = False

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.logger"
        ) as test_logger, patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ):
            await handle_mcp_message(
                conn, Mock(), self._valid_payload(label=secret, speak=False)
            )

        logged_values = " ".join(str(call) for call in test_logger.mock_calls)
        self.assertNotIn(secret, logged_values)

    async def test_old_device_abort_is_cleared_for_new_reminder_sentence(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = True
        conn.tts_control_generation = 0
        conn.session_id = "old-abort-session"
        conn.websocket.send = AsyncMock()

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ):
            await handle_mcp_message(conn, Mock(), self._valid_payload())

        self.assertFalse(conn.client_abort)
        self.assertNotEqual("old-turn", conn.sentence_id)
        conn.tts.tts_one_sentence.assert_called_once()

    async def test_new_sentence_during_cancel_wins_over_reminder(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = True

        async def start_new_turn(_conn, _old_id):
            conn.sentence_id = "new-user-turn"
            conn.client_abort = False

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(side_effect=start_new_turn),
        ):
            await handle_mcp_message(conn, Mock(), self._valid_payload())

        self.assertEqual("new-user-turn", conn.sentence_id)
        conn.tts.tts_one_sentence.assert_not_called()
        conn.dialogue.put.assert_not_called()

    async def test_new_abort_during_cancel_wins_over_reminder(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.client_abort = True

        async def abort_new_notification(_conn, _old_id):
            conn.client_abort = True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(side_effect=abort_new_notification),
        ):
            await handle_mcp_message(conn, Mock(), self._valid_payload())

        conn.tts.tts_one_sentence.assert_not_called()
        conn.dialogue.put.assert_not_called()

    async def test_new_sentence_before_notification_task_starts_wins(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.abort_generation = 1
        conn.client_abort = True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ) as cancel:
            await McpTextMessageHandler().handle(
                conn, {"payload": self._valid_payload()}
            )
            conn.sentence_id = "new-user-turn"
            conn.client_abort = False
            await asyncio.sleep(0)

        cancel.assert_not_awaited()
        self.assertEqual("new-user-turn", conn.sentence_id)
        conn.tts.tts_one_sentence.assert_not_called()

    async def test_new_abort_before_notification_task_starts_wins(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.abort_generation = 1
        conn.client_abort = True

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            new=AsyncMock(return_value=True),
        ) as cancel:
            await McpTextMessageHandler().handle(
                conn, {"payload": self._valid_payload()}
            )
            conn.abort_generation = 2
            await asyncio.sleep(0)

        cancel.assert_not_awaited()
        conn.tts.tts_one_sentence.assert_not_called()

    async def test_real_asr_turn_before_notification_task_starts_wins(self):
        conn = SimpleNamespace(
            need_bind=False,
            client_is_speaking=False,
            client_listen_mode="realtime",
            client_abort=True,
            abort_generation=1,
            sentence_id="old-turn",
            last_tts_text="",
            mcp_client=Mock(),
            tts=Mock(),
            dialogue=Mock(),
        )
        pending_notifications = []

        def capture_notification(coroutine):
            pending_notifications.append(coroutine)
            return Mock()

        with patch(
            "core.handle.textHandler.mcpMessageHandler.asyncio.create_task",
            side_effect=capture_notification,
        ):
            await McpTextMessageHandler().handle(
                conn, {"payload": self._valid_payload()}
            )

        cancel_started = asyncio.Event()
        keep_cancel_pending = asyncio.Event()

        async def pending_user_cancel(_conn, _old_id):
            cancel_started.set()
            await keep_cancel_pending.wait()

        with patch.object(
            receiveAudioHandle,
            "cancelActiveLLMResponse",
            new=AsyncMock(side_effect=pending_user_cancel),
        ):
            user_turn = asyncio.create_task(
                receiveAudioHandle.startToChat(conn, "你好")
            )
            await cancel_started.wait()
            self.assertEqual(2, conn.abort_generation)
            with patch(
                "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
                new=AsyncMock(return_value=True),
            ) as notification_cancel:
                await pending_notifications[0]
            user_turn.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await user_turn

        notification_cancel.assert_not_awaited()
        self.assertTrue(conn.client_abort)
        self.assertEqual("old-turn", conn.sentence_id)
        conn.tts.tts_one_sentence.assert_not_called()

    async def test_unknown_notification_is_rejected(self):
        conn = Mock()
        secret = "UNKNOWN_PRIVATE_BODY"
        with patch(
            "core.providers.tools.device_mcp.mcp_handler.logger"
        ) as test_logger:
            await handle_mcp_message(
                conn,
                Mock(),
                {
                    "method": "notifications/schedule/unknown",
                    "params": {"label": secret, "speak": True},
                },
            )

        conn.tts.tts_one_sentence.assert_not_called()
        logged_values = " ".join(str(call) for call in test_logger.mock_calls)
        self.assertNotIn(secret, logged_values)

    async def test_background_notification_exception_is_logged(self):
        conn = SimpleNamespace(
            sentence_id="old-turn",
            abort_generation=1,
            mcp_client=Mock(),
            logger=Mock(),
        )

        with patch(
            "core.handle.textHandler.mcpMessageHandler.handle_mcp_message",
            new=AsyncMock(side_effect=RuntimeError("boom")),
        ):
            await McpTextMessageHandler().handle(
                conn, {"payload": self._valid_payload()}
            )
            await asyncio.sleep(0)

        conn.logger.bind.return_value.error.assert_called_once()

    async def test_schedule_description_reaches_main_llm_unchanged(self):
        description = (
            "支持‘半小时后提醒我喝水’等自然表达；缺少时间或提醒内容时必须先追问。"
        )
        client = MCPClient()
        await client.add_tool(
            {
                "name": "self.schedule.create",
                "description": description,
                "inputSchema": {
                    "type": "object",
                    "properties": {"when": {"type": "string"}},
                    "required": ["when"],
                },
            }
        )

        tool = client.get_available_tools()[0]["function"]
        self.assertEqual("self_schedule_create", tool["name"])
        self.assertEqual(description, tool["description"])
        self.assertIn("自然表达", tool["description"])
        self.assertIn("必须先追问", tool["description"])


class DeviceMcpExecutorTest(unittest.IsolatedAsyncioTestCase):
    async def test_device_response_is_authoritative_and_skips_second_llm(self):
        conn = Mock()
        conn.mcp_client.is_ready = AsyncMock(return_value=True)
        executor = DeviceMCPExecutor(conn)

        with patch(
            "core.providers.tools.device_mcp.mcp_executor.call_mcp_tool",
            new=AsyncMock(
                return_value='{"action":"RESPONSE","response":"提醒已设置"}'
            ),
        ):
            result = await executor.execute(
                conn, "self_schedule_create", {"label": "喝水"}
            )

        self.assertEqual(Action.RESPONSE, result.action)
        self.assertEqual("提醒已设置", result.response)

    async def test_device_failure_is_error_and_never_claims_success(self):
        conn = Mock()
        conn.mcp_client.is_ready = AsyncMock(return_value=True)
        executor = DeviceMCPExecutor(conn)

        with patch(
            "core.providers.tools.device_mcp.mcp_executor.call_mcp_tool",
            new=AsyncMock(side_effect=RuntimeError("设备写入失败")),
        ):
            result = await executor.execute(
                conn, "self_schedule_create", {"label": "喝水"}
            )

        self.assertEqual(Action.ERROR, result.action)
        self.assertIn("设备写入失败", result.response)
        self.assertNotIn("成功", result.response)

    async def test_device_error_response_is_authoritative(self):
        conn = Mock()
        conn.mcp_client.is_ready = AsyncMock(return_value=True)
        executor = DeviceMCPExecutor(conn)

        with patch(
            "core.providers.tools.device_mcp.mcp_executor.call_mcp_tool",
            new=AsyncMock(
                return_value='{"action":"ERROR","response":"缺少提醒时间"}'
            ),
        ):
            result = await executor.execute(conn, "self_schedule_create", {})

        self.assertEqual(Action.ERROR, result.action)
        self.assertEqual("缺少提醒时间", result.response)


if __name__ == "__main__":
    unittest.main()
