import unittest
import asyncio
from unittest.mock import AsyncMock, Mock, patch

from plugins_func.functions.handle_exit_intent import handle_exit_intent
from core.handle.intentHandler import check_direct_exit
from core.providers.tts.dto.dto import SentenceType


class ExitIntentTest(unittest.TestCase):
    def test_exit_reply_is_always_concise(self):
        conn = type("Conn", (), {"close_after_chat": False})()

        response = handle_exit_intent(conn, "时间过得真快，让我们依依不舍地告别")

        self.assertTrue(conn.close_after_chat)
        self.assertEqual(response.response, "再见")

    def test_direct_exit_queues_concise_goodbye_before_close(self):
        tts = type(
            "TTS",
            (),
            {
                "tts_text_queue": type(
                    "Queue", (), {"items": [], "put": lambda self, item: self.items.append(item)}
                )(),
                "tts_one_sentence": Mock(),
            },
        )()
        logger = Mock()
        logger.bind.return_value = logger
        conn = type(
            "Conn",
            (),
            {
                "cmd_exit": ["再见"],
                "logger": logger,
                "tts": tts,
                "sentence_id": None,
                "close_after_chat": False,
            },
        )()

        with patch(
            "core.handle.intentHandler.send_stt_message",
            new=AsyncMock(),
        ):
            handled = asyncio.run(check_direct_exit(conn, "再见"))

        self.assertTrue(handled)
        self.assertTrue(conn.close_after_chat)
        self.assertEqual(tts.tts_text_queue.items[0].sentence_type, SentenceType.FIRST)
        self.assertEqual(tts.tts_text_queue.items[-1].sentence_type, SentenceType.LAST)
        tts.tts_one_sentence.assert_called_once()
        self.assertEqual(tts.tts_one_sentence.call_args.kwargs["content_detail"], "再见")


if __name__ == "__main__":
    unittest.main()
