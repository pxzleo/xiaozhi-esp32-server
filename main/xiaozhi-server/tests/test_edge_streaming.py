import asyncio
import threading
import unittest
from unittest.mock import patch

from core.providers.tts import edge as edge_module
from core.providers.tts.dto.dto import SentenceType


class _FakeAsyncReader:
    def __init__(self, chunks):
        self._chunks = list(chunks)

    async def read(self, _size=-1):
        return self._chunks.pop(0) if self._chunks else b""


class _FakeAsyncWriter:
    def __init__(self):
        self.data = bytearray()
        self.closed = False

    def write(self, data):
        self.data.extend(data)

    async def drain(self):
        return None

    def close(self):
        self.closed = True

    def is_closing(self):
        return self.closed

    async def wait_closed(self):
        return None


class _FakeProcess:
    def __init__(self, pcm_chunks=None, returncode=0, stderr=b""):
        self.stdin = _FakeAsyncWriter()
        self.stdout = _FakeAsyncReader(pcm_chunks or [b"\x01\x00" * 1440, b""])
        self.stderr = _FakeAsyncReader([stderr, b""])
        self.returncode = returncode
        self.terminated = False
        self.waited = False

    async def wait(self):
        self.waited = True
        return self.returncode

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.terminated = True


class _FakeCommunicate:
    def __init__(self, *_args, **_kwargs):
        pass

    async def stream(self):
        yield {"type": "audio", "data": b"mp3-audio-chunk"}


class _FailingCommunicate:
    def __init__(self, *_args, **_kwargs):
        pass

    async def stream(self):
        if False:
            yield None
        raise ConnectionError("edge stream failed")


class _FakeEncoder:
    def __init__(self, events):
        self.events = events

    def encode_pcm_to_opus_stream(self, pcm_data, end_of_stream, callback):
        if pcm_data:
            callback(b"opus-frame")
        if end_of_stream:
            self.events.append(("flush", None))


class _RecordingQueue:
    def __init__(self, events):
        self.events = events

    def put(self, item):
        self.events.append(("queue", item))


class EdgeStreamingTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.provider = edge_module.TTSProvider(
            {"voice": "zh-CN-XiaoxiaoNeural", "output_dir": "tmp/"}, True
        )
        self.provider.conn = type(
            "Conn",
            (),
            {
                "sample_rate": 24000,
                "client_abort": False,
                "sentence_id": "sentence-1",
                "stop_event": threading.Event(),
            },
        )()
        self.provider.current_sentence_id = "sentence-1"
        self.provider.opus_encoder = _FakeEncoder(self.events)
        self.provider.tts_audio_queue = _RecordingQueue(self.events)

    def test_short_opening_is_merged_with_following_sentence(self):
        self.provider.tts_text_buff = ["嘿嘿，"]

        self.assertIsNone(self.provider._get_segment_text())
        self.assertEqual(self.provider.processed_chars, 0)

        self.provider.tts_text_buff.append("蛋妞现在脑子转得很快。")

        self.assertEqual(
            self.provider._get_segment_text(),
            "嘿嘿，蛋妞现在脑子转得很快",
        )
        self.assertEqual(
            self.provider.processed_chars,
            len("嘿嘿，蛋妞现在脑子转得很快。"),
        )

    def test_leading_fillers_are_removed_deterministically(self):
        self.assertEqual(
            self.provider._sanitize_spoken_text("哈哈你是在说游戏吗？"),
            "你是在说游戏吗？",
        )
        self.assertEqual(
            self.provider._sanitize_spoken_text("嘿嘿，哎呀～我听见了"),
            "我听见了",
        )

    def test_partial_stream_failure_sends_explicit_notice(self):
        with patch.object(self.provider, "to_tts_stream") as to_tts_stream:
            self.provider._notify_partial_stream_failure(
                "这是原回复", self.provider.handle_opus
            )

        to_tts_stream.assert_called_once_with(
            self.provider.PARTIAL_STREAM_FAILURE_NOTICE,
            opus_handler=self.provider.handle_opus,
        )

    def test_meaningful_first_segment_still_streams_immediately(self):
        self.provider.tts_text_buff = ["我现在就来帮你，后面还有内容。"]

        self.assertEqual(self.provider._get_segment_text(), "我现在就来帮你")
        self.assertEqual(self.provider.processed_chars, len("我现在就来帮你，"))

    def test_following_sentences_wait_for_final_merged_request(self):
        self.provider.is_first_sentence = False
        self.provider.tts_text_buff = ["第一句话。第二句话。"]

        self.assertIsNone(self.provider._get_segment_text())
        self.assertEqual(self.provider.processed_chars, 0)

        with patch.object(self.provider, "to_tts_stream") as to_tts_stream:
            self.assertTrue(self.provider._process_remaining_text_stream())

        to_tts_stream.assert_called_once_with(
            "第一句话。第二句话", opus_handler=None
        )

    def test_complete_short_reply_is_flushed_at_end(self):
        self.provider.tts_text_buff = ["好的。"]

        self.assertIsNone(self.provider._get_segment_text())
        with patch.object(self.provider, "to_tts_stream") as to_tts_stream:
            self.assertTrue(self.provider._process_remaining_text_stream())

        to_tts_stream.assert_called_once_with("好的", opus_handler=None)
        self.assertEqual(self.provider.processed_chars, len("好的。"))

    def test_trailing_filler_does_not_create_an_edge_request(self):
        self.provider.tts_text_buff = ["嘿嘿~"]

        with patch.object(self.provider, "to_tts_stream") as to_tts_stream:
            self.assertFalse(self.provider._process_remaining_text_stream())

        to_tts_stream.assert_not_called()
        self.assertEqual(self.provider.processed_chars, len("嘿嘿~"))

    @patch.object(edge_module.edge_tts, "Communicate", _FakeCommunicate)
    def test_first_marker_precedes_incremental_opus_audio(self):
        process = _FakeProcess()

        async def create_process(*args, **kwargs):
            self.assertIn("ffmpeg", args[0])
            self.assertIn("24000", args)
            return process

        with patch.object(
            edge_module.asyncio,
            "create_subprocess_exec",
            side_effect=create_process,
        ):
            self.provider.to_tts_stream(
                "你好", opus_handler=lambda data: self.events.append(("audio", data))
            )

        first_marker = next(
            index
            for index, event in enumerate(self.events)
            if event[0] == "queue" and event[1][0] is SentenceType.FIRST
        )
        first_audio = next(
            index for index, event in enumerate(self.events) if event[0] == "audio"
        )
        self.assertLess(first_marker, first_audio)
        self.assertEqual(process.stdin.data, b"mp3-audio-chunk")
        self.assertTrue(process.stdin.closed)
        self.assertIn(("flush", None), self.events)

    @patch.object(edge_module.edge_tts, "Communicate", _FakeCommunicate)
    def test_ffmpeg_failure_is_explicit(self):
        process = _FakeProcess(pcm_chunks=[b""], returncode=1, stderr=b"decode failed")

        async def create_process(*_args, **_kwargs):
            return process

        with patch.object(
            edge_module.asyncio,
            "create_subprocess_exec",
            side_effect=create_process,
        ):
            with self.assertRaisesRegex(RuntimeError, "decode failed"):
                asyncio.run(
                    self.provider._stream_text_to_opus(
                        "你好", lambda _data: None, lambda: None
                    )
                )

    @patch.object(edge_module.edge_tts, "Communicate", _FailingCommunicate)
    def test_edge_failure_still_closes_and_waits_for_ffmpeg(self):
        process = _FakeProcess(pcm_chunks=[b""])

        async def create_process(*_args, **_kwargs):
            return process

        with patch.object(
            edge_module.asyncio,
            "create_subprocess_exec",
            side_effect=create_process,
        ):
            with self.assertRaisesRegex(ConnectionError, "edge stream failed"):
                asyncio.run(
                    self.provider._stream_text_to_opus(
                        "你好", lambda _data: None, lambda: None
                    )
                )

        self.assertTrue(process.stdin.closed)
        self.assertTrue(process.waited)


if __name__ == "__main__":
    unittest.main()
