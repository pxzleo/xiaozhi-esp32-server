import os
import queue
import tempfile
import threading
import unittest
from unittest.mock import AsyncMock, patch

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.providers.tts.index_stream import TTSProvider


class _ConnectionStub:
    def __init__(self):
        self.stop_event = threading.Event()
        self.client_abort = False
        self.sentence_id = "sentence-1"


class IndexStreamFilePlaybackTest(unittest.TestCase):
    def test_leading_fillers_are_removed_before_streaming_tts(self):
        provider = object.__new__(TTSProvider)
        provider._correct_words_pattern = None
        provider.text_to_speak = AsyncMock()

        with patch("core.providers.tts.index_stream.logger"):
            self.assertTrue(provider.to_tts_single_stream("嘿嘿，哎呀～你好", True))

        provider.text_to_speak.assert_awaited_once_with("你好", True)

    def test_only_leading_fillers_do_not_send_empty_tts_request(self):
        provider = object.__new__(TTSProvider)
        provider._correct_words_pattern = None
        provider.text_to_speak = AsyncMock()
        provider._process_before_stop_play_files = unittest.mock.Mock()

        self.assertTrue(provider.to_tts_single_stream("嘿嘿，哎呀～", True))

        provider.text_to_speak.assert_not_awaited()
        provider._process_before_stop_play_files.assert_called_once_with()

    def test_prompt_tts_failure_does_not_end_owned_music_session(self):
        provider = object.__new__(TTSProvider)
        provider.conn = _ConnectionStub()
        provider.tts_audio_queue = queue.Queue()
        provider.current_sentence_id = "sentence-1"
        provider.conn.server_audio_playback_sentence_id = "sentence-1"

        provider._enqueue_tts_error_end()

        with self.assertRaises(queue.Empty):
            provider.tts_audio_queue.get_nowait()

    def test_regular_tts_failure_keeps_sentence_id_on_last(self):
        provider = object.__new__(TTSProvider)
        provider.conn = _ConnectionStub()
        provider.tts_audio_queue = queue.Queue()
        provider.current_sentence_id = "sentence-1"

        provider._enqueue_tts_error_end()

        sentence_type, audio_data, text, sentence_id = (
            provider.tts_audio_queue.get_nowait()
        )
        self.assertEqual(sentence_type, SentenceType.LAST)
        self.assertEqual(audio_data, [])
        self.assertIsNone(text)
        self.assertEqual(sentence_id, "sentence-1")

    def test_old_file_frames_are_discarded_after_turn_changes(self):
        provider = object.__new__(TTSProvider)
        provider.conn = _ConnectionStub()
        provider.tts_text_queue = queue.Queue()
        provider.tts_audio_queue = queue.Queue()
        provider.before_stop_play_files = []
        provider.current_sentence_id = "sentence-1"
        provider.tts_text_buff = []
        provider.processed_chars = 0

        processed = threading.Event()

        def process_audio_file(_path, callback):
            provider.conn.sentence_id = "sentence-2"
            try:
                callback(b"stale-frame")
            finally:
                processed.set()
                provider.conn.stop_event.set()

        provider._process_audio_file_stream = process_audio_file

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as audio_file:
            audio_path = audio_file.name

        try:
            provider.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id="sentence-1",
                    sentence_type=SentenceType.MIDDLE,
                    content_type=ContentType.FILE,
                    content_file=audio_path,
                )
            )
            worker = threading.Thread(target=provider.tts_text_priority_thread)
            worker.start()
            worker.join(timeout=2)

            self.assertTrue(processed.is_set())
            with self.assertRaises(queue.Empty):
                provider.tts_audio_queue.get_nowait()
        finally:
            os.unlink(audio_path)

    def test_pending_prompt_starts_sentence_before_audio_file_frames(self):
        provider = object.__new__(TTSProvider)
        provider.conn = _ConnectionStub()
        provider.tts_text_queue = queue.Queue()
        provider.tts_audio_queue = queue.Queue()
        provider.before_stop_play_files = []
        provider.current_sentence_id = "sentence-1"
        provider.tts_text_buff = []
        provider.processed_chars = 0
        provider.is_first_sentence = True
        provider.tts_stop_request = False
        provider.first_sentence_punctuations = ("，", "。")
        provider.punctuations = ("。",)

        processed = threading.Event()

        def synthesize_prompt(text, is_last=False):
            provider.tts_audio_queue.put(
                (SentenceType.FIRST, b"prompt-frame", text, "sentence-1")
            )

        def process_audio_file(_path, callback):
            callback(b"music-frame")
            processed.set()
            provider.conn.stop_event.set()

        provider.to_tts_single_stream = synthesize_prompt
        provider._process_audio_file_stream = process_audio_file

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as audio_file:
            audio_path = audio_file.name

        try:
            provider.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id="sentence-1",
                    sentence_type=SentenceType.MIDDLE,
                    content_type=ContentType.TEXT,
                    content_detail="正在播放每日推荐",
                )
            )
            provider.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id="sentence-1",
                    sentence_type=SentenceType.MIDDLE,
                    content_type=ContentType.FILE,
                    content_detail="test song",
                    content_file=audio_path,
                )
            )
            worker = threading.Thread(target=provider.tts_text_priority_thread)
            worker.start()
            worker.join(timeout=2)

            self.assertTrue(processed.is_set())
            prompt = provider.tts_audio_queue.get_nowait()
            music = provider.tts_audio_queue.get_nowait()
            self.assertEqual(prompt[0], SentenceType.FIRST)
            self.assertEqual(prompt[1], b"prompt-frame")
            self.assertEqual(music[0], SentenceType.MIDDLE)
            self.assertEqual(music[1], b"music-frame")
        finally:
            os.unlink(audio_path)

    def test_audio_file_frames_are_enqueued_before_last_message(self):
        provider = object.__new__(TTSProvider)
        provider.conn = _ConnectionStub()
        provider.tts_text_queue = queue.Queue()
        provider.tts_audio_queue = queue.Queue()
        provider.before_stop_play_files = []
        provider.current_sentence_id = "sentence-1"
        provider.tts_text_buff = []
        provider.processed_chars = 0

        processed = threading.Event()

        def process_audio_file(_path, callback):
            callback(b"first-frame")
            processed.set()
            provider.conn.stop_event.set()

        provider._process_audio_file_stream = process_audio_file

        with tempfile.NamedTemporaryFile(suffix=".mp3", delete=False) as audio_file:
            audio_path = audio_file.name

        try:
            provider.tts_text_queue.put(
                TTSMessageDTO(
                    sentence_id="sentence-1",
                    sentence_type=SentenceType.MIDDLE,
                    content_type=ContentType.FILE,
                    content_detail="test song",
                    content_file=audio_path,
                )
            )
            worker = threading.Thread(target=provider.tts_text_priority_thread)
            worker.start()
            worker.join(timeout=2)

            self.assertTrue(processed.is_set())
            sentence_type, audio_data, text, sentence_id = provider.tts_audio_queue.get_nowait()
            self.assertEqual(sentence_type, SentenceType.MIDDLE)
            self.assertEqual(audio_data, b"first-frame")
            self.assertIsNone(text)
            self.assertEqual(sentence_id, "sentence-1")
            self.assertEqual(provider.before_stop_play_files, [])
        finally:
            os.unlink(audio_path)


if __name__ == "__main__":
    unittest.main()
