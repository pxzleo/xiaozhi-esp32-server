import asyncio
import threading
import time
import unittest
from unittest.mock import AsyncMock, Mock, patch

from core.connection import ConnectionHandler
from core.handle import receiveAudioHandle
from plugins_func.register import Action, ActionResponse


class _RecordingExecutor:
    def __init__(self):
        self.submissions = []

    def submit(self, *args):
        self.submissions.append(args)


class _Dialogue:
    def __init__(self):
        self.messages = []

    def put(self, message):
        self.messages.append(message)


class ChatTurnIsolationTest(unittest.TestCase):
    def test_matching_tts_text_is_treated_as_echo_while_speaking(self):
        conn = type(
            "Conn",
            (),
            {
                "client_is_speaking": True,
                "last_tts_text": "蛋妞听得见呢，你慢慢说。",
            },
        )()

        self.assertTrue(
            receiveAudioHandle.is_likely_tts_echo(conn, "蛋妞听得见呢")
        )

    def test_unrelated_barge_in_is_not_treated_as_echo(self):
        conn = type(
            "Conn",
            (),
            {
                "client_is_speaking": True,
                "last_tts_text": "蛋妞听得见呢，你慢慢说。",
            },
        )()

        self.assertFalse(
            receiveAudioHandle.is_likely_tts_echo(conn, "帮我查询明天的天气")
        )

    def test_previous_tts_segment_is_treated_as_echo_after_next_segment_starts(self):
        now = time.monotonic()
        conn = type(
            "Conn",
            (),
            {
                "client_is_speaking": True,
                "last_tts_text": "小狐狸每天晚上都坐在山顶看月亮。",
                "recent_tts_texts": [
                    (now - 2, "好嘞，那给你讲个小狐狸和月亮的故事呀"),
                    (now - 1, "小狐狸每天晚上都坐在山顶看月亮。"),
                ],
            },
        )()

        self.assertTrue(
            receiveAudioHandle.is_likely_tts_echo(
                conn, "好嘞，那给你讲个小葫芦和月亮的故事啊"
            )
        )

    def test_short_command_inside_tts_text_is_kept_as_barge_in(self):
        now = time.monotonic()
        conn = type(
            "Conn",
            (),
            {
                "client_is_speaking": True,
                "last_tts_text": "你可以说停止播放来结束当前内容。",
                "recent_tts_texts": [
                    (now - 1, "你可以说停止播放来结束当前内容。"),
                ],
            },
        )()

        self.assertFalse(
            receiveAudioHandle.is_likely_tts_echo(conn, "停止播放")
        )

    def test_expired_tts_text_is_not_treated_as_echo(self):
        expired_at = (
            time.monotonic()
            - receiveAudioHandle.TTS_ECHO_HISTORY_TTL_SECONDS
            - 1
        )
        conn = type(
            "Conn",
            (),
            {
                "client_is_speaking": True,
                "last_tts_text": "蛋妞听得见呢，你慢慢说。",
                "last_tts_text_at": expired_at,
                "recent_tts_texts": [
                    (expired_at, "蛋妞听得见呢，你慢慢说。"),
                ],
            },
        )()

        self.assertFalse(
            receiveAudioHandle.is_likely_tts_echo(conn, "蛋妞听得见呢")
        )

    def test_json_asr_content_is_used_for_echo_filter(self):
        logger = Mock()
        logger.bind.return_value = logger
        conn = type(
            "Conn",
            (),
            {
                "need_bind": False,
                "client_is_speaking": True,
                "last_tts_text": "蛋妞听得见呢，你慢慢说。",
                "introduced_speakers": set(),
                "current_speaker": None,
                "logger": logger,
            },
        )()

        with patch.object(
            receiveAudioHandle, "handle_user_intent", new=AsyncMock()
        ) as handle_user_intent:
            asyncio.run(
                receiveAudioHandle.startToChat(
                    conn,
                    '{"content":"蛋妞听得见呢","language":"zh"}',
                )
            )

        handle_user_intent.assert_not_awaited()
        logger.info.assert_called_once()

    def test_start_to_chat_assigns_turn_id_before_submitting_worker(self):
        executor = _RecordingExecutor()
        conn = type(
            "Conn",
            (),
            {
                "need_bind": False,
                "max_output_size": 0,
                "headers": {},
                "client_is_speaking": False,
                "client_listen_mode": "realtime",
                "introduced_speakers": set(),
                "current_speaker": None,
                "client_abort": True,
                "sentence_id": "old-turn",
                "executor": executor,
                "chat": Mock(),
            },
        )()

        with patch.object(
            receiveAudioHandle, "handle_user_intent", new=AsyncMock(return_value=False)
        ), patch.object(
            receiveAudioHandle, "send_stt_message", new=AsyncMock()
        ):
            asyncio.run(receiveAudioHandle.startToChat(conn, "你好"))

        self.assertNotEqual(conn.sentence_id, "old-turn")
        self.assertFalse(conn.client_abort)
        self.assertEqual(len(executor.submissions), 1)
        submission = executor.submissions[0]
        self.assertIs(submission[0], conn.chat)
        self.assertEqual(submission[1:3], ("你好", 0))
        self.assertEqual(submission[3], conn.sentence_id)

    def test_cancelled_tool_result_cannot_reenter_new_turn(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.sentence_id = "new-turn"
        conn.client_abort = False
        conn.stop_event = threading.Event()
        conn.dialogue = _Dialogue()
        conn.chat = Mock()

        conn._handle_function_result(
            [
                (
                    ActionResponse(Action.REQLLM, result="旧轮工具结果"),
                    {"id": "tool-1", "name": "web_search", "arguments": "{}"},
                )
            ],
            depth=0,
            current_sentence_id="old-turn",
        )

        conn.chat.assert_not_called()
        self.assertEqual(conn.dialogue.messages, [])

    def test_active_tool_recursion_keeps_original_turn_id(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.sentence_id = "active-turn"
        conn.client_abort = False
        conn.stop_event = threading.Event()
        conn.dialogue = _Dialogue()
        conn.chat = Mock()

        conn._handle_function_result(
            [
                (
                    ActionResponse(Action.REQLLM, result="当前轮工具结果"),
                    {"id": "tool-1", "name": "web_search", "arguments": "{}"},
                )
            ],
            depth=0,
            current_sentence_id="active-turn",
        )

        conn.chat.assert_called_once_with(
            None,
            depth=1,
            current_sentence_id="active-turn",
        )


if __name__ == "__main__":
    unittest.main()
