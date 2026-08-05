import asyncio
import threading
import unittest
from unittest.mock import AsyncMock, Mock, patch

from core.connection import ConnectionHandler


class _Memory:
    def __init__(self, saved):
        self.saved = saved

    async def save_memory(self, _dialogue, _session_id):
        self.saved.set()


class ConnectionLifecycleTest(unittest.TestCase):
    def test_title_failure_does_not_skip_memory_summary(self):
        saved = threading.Event()
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.session_id = "session-1"
        conn._postprocessing_started = False
        conn.dialogue = type("Dialogue", (), {"dialogue": []})()
        conn.memory = _Memory(saved)
        conn.server = None
        conn.logger = Mock()
        conn.logger.bind.return_value = conn.logger
        conn.close = AsyncMock()

        async def fail_title(_session_id):
            raise RuntimeError("标题服务失败")

        with patch(
            "core.connection.generate_and_save_chat_title",
            side_effect=fail_title,
        ):
            asyncio.run(conn._save_and_close(None))

        self.assertTrue(saved.wait(0.5))
        conn.close.assert_awaited_once_with(None)

    def test_close_retries_after_failed_cleanup(self):
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn._close_lock = asyncio.Lock()
        conn._closed = False
        conn._close_resources = AsyncMock(side_effect=[False, True])

        asyncio.run(conn.close())
        self.assertFalse(conn._closed)

        asyncio.run(conn.close())
        self.assertTrue(conn._closed)
        self.assertEqual(conn._close_resources.await_count, 2)


if __name__ == "__main__":
    unittest.main()
