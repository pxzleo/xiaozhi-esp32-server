import asyncio
import queue
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from core.connection import ConnectionHandler, get_tool_call_notice
from core.handle import receiveAudioHandle
from core.handle.abortHandle import handleAbortMessage
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
    def _mobile_barge_in_conn(self, vad_results):
        logger = Mock()
        logger.bind.return_value = logger
        return SimpleNamespace(
            close_after_chat=False,
            client_kind="mobile",
            client_aec=True,
            client_is_speaking=True,
            client_listen_mode="realtime",
            just_woken_up=False,
            vad=Mock(is_vad=Mock(side_effect=vad_results)),
            asr=SimpleNamespace(receive_audio=AsyncMock()),
            logger=logger,
            client_have_voice=True,
            client_voice_stop=False,
            asr_audio=[],
            last_activity_time=0,
        )

    def test_mobile_speaker_echo_does_not_abort_before_confirmation_window(self):
        conn = self._mobile_barge_in_conn([True])

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock()
        ) as abort:
            asyncio.run(receiveAudioHandle.handleAudioMessage(conn, b"frame"))

        abort.assert_not_awaited()
        conn.asr.receive_audio.assert_not_awaited()

    def test_mobile_confirmation_buffer_keeps_only_bounded_preroll(self):
        conn = self._mobile_barge_in_conn([False] * 100 + [True] * 6)

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock()
        ) as abort:
            for index in range(100):
                asyncio.run(
                    receiveAudioHandle.handleAudioMessage(
                        conn, f"silence-{index}".encode()
                    )
                )
            for index in range(6):
                asyncio.run(
                    receiveAudioHandle.handleAudioMessage(conn, f"voice-{index}".encode())
                )

        abort.assert_awaited_once_with(conn)
        forwarded = [call.args[1] for call in conn.asr.receive_audio.await_args_list]
        self.assertEqual(
            [b"silence-98", b"silence-99"]
            + [f"voice-{index}".encode() for index in range(6)],
            forwarded,
        )

    def test_mobile_confirmation_requires_consecutive_voice_packets(self):
        conn = self._mobile_barge_in_conn([True] * 3 + [False] + [True] * 3)

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock()
        ) as abort:
            for _ in range(7):
                asyncio.run(receiveAudioHandle.handleAudioMessage(conn, b"frame"))

        abort.assert_not_awaited()
        conn.asr.receive_audio.assert_not_awaited()
        self.assertEqual(3, conn._mobile_barge_in_packets)
        self.assertEqual(4, len(conn._mobile_barge_in_frames))

    def test_tts_round_boundary_discards_unconfirmed_mobile_audio(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.logger = Mock()
        conn.logger.bind.return_value = conn.logger
        conn.client_is_speaking = True
        conn._mobile_barge_in_packets = 3
        conn._mobile_barge_in_active = True
        conn._mobile_barge_in_confirmed = False
        conn._mobile_barge_in_frames = [b"echo"]
        conn.reset_audio_states = Mock()

        conn.clearSpeakStatus()

        conn.reset_audio_states.assert_called_once_with()
        self.assertFalse(conn.client_is_speaking)
        self.assertEqual(0, conn._mobile_barge_in_packets)
        self.assertEqual([], conn._mobile_barge_in_frames)

    def test_tts_round_boundary_discards_vad_tail_after_counter_reset(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.logger = Mock()
        conn.logger.bind.return_value = conn.logger
        conn.client_is_speaking = True
        conn.client_have_voice = True
        conn._mobile_barge_in_packets = 0
        conn._mobile_barge_in_active = True
        conn._mobile_barge_in_confirmed = False
        conn._mobile_barge_in_frames = []
        conn._mobile_barge_in_preroll = [b"silence"]
        conn.reset_audio_states = Mock()

        conn.clearSpeakStatus()

        conn.reset_audio_states.assert_called_once_with()
        self.assertFalse(conn.client_is_speaking)

    def test_non_mobile_clients_do_not_reset_audio_at_tts_boundary(self):
        for client_kind, client_aec, listen_mode in (
            ("device", True, "realtime"),
            ("mobile", False, "realtime"),
            ("mobile", True, "manual"),
        ):
            with self.subTest(
                client_kind=client_kind,
                client_aec=client_aec,
                listen_mode=listen_mode,
            ):
                conn = ConnectionHandler.__new__(ConnectionHandler)
                conn.logger = Mock()
                conn.logger.bind.return_value = conn.logger
                conn.client_kind = client_kind
                conn.client_aec = client_aec
                conn.client_listen_mode = listen_mode
                conn.client_is_speaking = True
                conn.client_have_voice = True
                conn.reset_audio_states = Mock()

                conn.clearSpeakStatus()

                conn.reset_audio_states.assert_not_called()

    def test_mobile_confirmation_flushes_buffer_even_when_abort_clears_gate(self):
        conn = self._mobile_barge_in_conn([True] * 6)

        async def abort_and_clear(_conn):
            receiveAudioHandle._clear_mobile_barge_in_gate(_conn)
            _conn.client_is_speaking = False

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock(side_effect=abort_and_clear)
        ):
            for index in range(6):
                asyncio.run(
                    receiveAudioHandle.handleAudioMessage(conn, f"voice-{index}".encode())
                )

        forwarded = [call.args[1] for call in conn.asr.receive_audio.await_args_list]
        self.assertEqual([f"voice-{index}".encode() for index in range(6)], forwarded)

    def test_mobile_continuous_speech_aborts_after_confirmation_window(self):
        conn = self._mobile_barge_in_conn([True] * 7)

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock()
        ) as abort:
            for _ in range(7):
                asyncio.run(receiveAudioHandle.handleAudioMessage(conn, b"frame"))

        abort.assert_awaited_once_with(conn)
        self.assertEqual(conn.asr.receive_audio.await_count, 7)
        self.assertTrue(all(call.args[-1] for call in conn.asr.receive_audio.await_args_list))

    def test_short_mobile_speaker_echo_is_discarded_when_vad_stops(self):
        conn = self._mobile_barge_in_conn([True, False])

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock()
        ) as abort:
            asyncio.run(receiveAudioHandle.handleAudioMessage(conn, b"voice"))
            conn.asr_audio.append(b"voice")
            conn.client_voice_stop = True
            asyncio.run(receiveAudioHandle.handleAudioMessage(conn, b"silence"))

        abort.assert_not_awaited()
        self.assertEqual([], conn.asr_audio)
        self.assertFalse(conn.client_have_voice)
        self.assertFalse(conn.client_voice_stop)

    def test_device_aec_barge_in_keeps_immediate_abort(self):
        conn = self._mobile_barge_in_conn([True])
        conn.client_kind = "device"

        with patch.object(
            receiveAudioHandle, "handleAbortMessage", new=AsyncMock()
        ) as abort:
            asyncio.run(receiveAudioHandle.handleAudioMessage(conn, b"frame"))

        abort.assert_awaited_once_with(conn)
        conn.asr.receive_audio.assert_awaited_once_with(conn, b"frame", True)

    def test_closing_dialogue_ignores_late_asr_result(self):
        logger = Mock()
        logger.bind.return_value = logger
        conn = SimpleNamespace(
            close_after_chat=True,
            logger=logger,
            need_bind=False,
        )

        with patch.object(
            receiveAudioHandle, "handle_user_intent", new=AsyncMock()
        ) as handle_user_intent:
            asyncio.run(receiveAudioHandle.startToChat(conn, "还有瞄准的"))

        handle_user_intent.assert_not_awaited()
        logger.info.assert_called_once_with("对话正在关闭，忽略迟到的ASR结果")

    def test_tool_call_notice_describes_search(self):
        self.assertEqual(
            get_tool_call_notice([{"name": "web_search"}]),
            "我来处理一下。",
        )

    def test_other_tools_do_not_have_tool_notice(self):
        self.assertIsNone(get_tool_call_notice([{"name": "get_weather"}]))

    def test_rag_search_notice_describes_lookup(self):
        self.assertEqual(
            get_tool_call_notice([{"name": "search_from_ragflow"}]),
            "我来处理一下。",
        )

    def test_mixed_tool_calls_only_use_search_notice(self):
        self.assertEqual(
            get_tool_call_notice(
                [{"name": "get_weather"}, {"name": "web_search"}]
            ),
            "我来处理一下。",
        )

    def test_direct_answer_does_not_have_tool_notice(self):
        self.assertIsNone(get_tool_call_notice([{"name": "direct_answer"}]))

    def test_schedule_tool_does_not_have_tool_notice(self):
        self.assertIsNone(
            get_tool_call_notice([{"name": "self_schedule_create"}])
        )

    def test_music_tool_does_not_stream_model_or_direct_answer_preamble(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.server = None
        conn.logger = Mock()
        conn.logger.bind.return_value = conn.logger
        conn.sentence_id = "turn-1"
        conn.client_abort = False
        conn.stop_event = threading.Event()
        conn.dialogue = Mock()
        conn.dialogue.get_llm_dialogue_with_memory.return_value = []
        conn.tts = Mock()
        conn.tts.tts_text_queue = queue.Queue()
        conn.memory = None
        conn.current_speaker = None
        conn.system_introduced_speakers = set()
        conn.intent_type = "function_call"
        conn.config = {"voiceprint": {}, "tool_call_timeout": 30}
        conn.session_id = "session-1"
        conn.features = {"emoji": False}
        conn.loop = Mock()
        conn.llm = Mock()
        conn.llm.response_with_functions.return_value = iter(
            [
                ("我来处理一下", None),
                (
                    None,
                    [
                        SimpleNamespace(
                            index=0,
                            id="direct-1",
                            function=SimpleNamespace(
                                name="direct_answer",
                                arguments='{"response":"我来处理一下"}',
                            ),
                        ),
                        SimpleNamespace(
                            index=1,
                            id="tool-1",
                            function=SimpleNamespace(
                                name="play_netease_music",
                                arguments='{"action":"next","name":""}',
                            ),
                        )
                    ],
                ),
            ]
        )
        conn.func_handler = Mock()
        conn.func_handler.get_functions.return_value = [
            {"type": "function", "function": {"name": "play_netease_music"}}
        ]
        conn.func_handler.handle_llm_function_call = AsyncMock()
        completed_future = Mock()
        completed_future.result.return_value = ActionResponse(
            Action.RESPONSE, response="正在播放下一首"
        )

        def complete_coroutine(coroutine, *_args, **_kwargs):
            coroutine.close()
            return completed_future

        with patch(
            "core.connection.asyncio.run_coroutine_threadsafe",
            side_effect=complete_coroutine,
        ), patch("core.connection.enqueue_tool_report"), patch.object(
            conn, "_handle_function_result"
        ):
            conn._chat_impl("下一首", current_sentence_id="turn-1")

        spoken_text = [
            item.content_detail
            for item in list(conn.tts.tts_text_queue.queue)
            if item.content_detail
        ]
        self.assertNotIn("我来处理一下", "".join(spoken_text))

    def test_music_fewshot_routes_playback_requests_to_real_tool(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.intent_type = "function_call"
        conn.func_handler = Mock()
        conn.func_handler.get_functions.return_value = [
            {"type": "function", "function": {"name": "play_netease_music"}}
        ]
        conn.dialogue = _Dialogue()
        conn.logger = Mock()
        conn.logger.bind.return_value = conn.logger

        conn._inject_tool_call_fewshot()

        examples = {
            message.content: conn.dialogue.messages[index + 1].tool_calls[0]
            for index, message in enumerate(conn.dialogue.messages[:-1])
            if message.role == "user"
            and message.content
            in {
                "随机播放",
                "下一首",
                "播放民谣",
                "播放热歌榜",
                "播放华语新碟",
                "播放周杰伦的专辑七里香",
                "播放类似的歌",
                "智能续播",
            }
        }
        self.assertEqual(
            examples["随机播放"]["function"],
            {
                "arguments": '{"action":"random","name":""}',
                "name": "play_netease_music",
            },
        )
        self.assertEqual(
            examples["下一首"]["function"],
            {
                "arguments": '{"action":"next","name":""}',
                "name": "play_netease_music",
            },
        )
        self.assertEqual(
            examples["播放民谣"]["function"]["arguments"],
            '{"action":"category","name":"民谣"}',
        )
        self.assertEqual(
            examples["播放热歌榜"]["function"]["arguments"],
            '{"action":"chart","name":"热歌榜"}',
        )
        self.assertEqual(
            examples["播放类似的歌"]["function"]["arguments"],
            '{"action":"similar","name":""}',
        )
        self.assertEqual(
            examples["智能续播"]["function"]["arguments"],
            '{"action":"intelligence","name":""}',
        )
        self.assertEqual(
            examples["播放华语新碟"]["function"]["arguments"],
            '{"action":"new_albums","name":"华语"}',
        )
        self.assertEqual(
            examples["播放周杰伦的专辑七里香"]["function"]["arguments"],
            '{"action":"album","name":"周杰伦 七里香"}',
        )

    def test_vague_deferred_intent_asks_before_creating_reminder(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.intent_type = "function_call"
        conn.func_handler = Mock()
        conn.func_handler.get_functions.return_value = [
            {"type": "function", "function": {"name": "self_schedule_create"}}
        ]
        conn.dialogue = _Dialogue()
        conn.logger = Mock()
        conn.logger.bind.return_value = conn.logger

        conn._inject_tool_call_fewshot()

        index = next(
            index
            for index, message in enumerate(conn.dialogue.messages)
            if message.role == "user" and message.content == "我晚点要交报告"
        )
        tool_call = conn.dialogue.messages[index + 1].tool_calls[0]
        self.assertEqual(tool_call["function"]["name"], "direct_answer")
        self.assertIn("几点提醒", tool_call["function"]["arguments"])

    def test_abort_cancels_active_llm_response(self):
        logger = Mock()
        logger.bind.return_value = logger
        llm = Mock()
        websocket = type("WebSocket", (), {"send": AsyncMock()})()
        conn = type(
            "Conn",
            (),
            {
                "logger": logger,
                "llm": llm,
                "websocket": websocket,
                "session_id": "session-1",
                "sentence_id": "turn-1",
                "close_after_chat": True,
                "client_abort": False,
                "clear_queues": Mock(),
                "clearSpeakStatus": Mock(),
            },
        )()

        asyncio.run(handleAbortMessage(conn))

        llm.cancel_response.assert_called_once_with("session-1", "turn-1")
        self.assertTrue(conn.client_abort)
        self.assertEqual(1, conn.abort_generation)
        conn.clear_queues.assert_called_once_with()
        conn.clearSpeakStatus.assert_called_once_with()

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
                "llm": Mock(),
                "session_id": "session-1",
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
        self.assertEqual(1, conn.abort_generation)
        self.assertEqual(len(executor.submissions), 1)
        submission = executor.submissions[0]
        self.assertIs(submission[0], conn.chat)
        self.assertEqual(submission[1:3], ("你好", 0))
        self.assertEqual(submission[3], conn.sentence_id)
        conn.llm.cancel_response.assert_called_once_with("session-1", "old-turn")

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
