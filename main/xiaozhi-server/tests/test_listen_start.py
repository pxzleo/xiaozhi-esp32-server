import asyncio
import unittest

from core.handle.textHandler.listenMessageHandler import ListenTextMessageHandler


class _FakeLogger:
    def bind(self, **_kwargs):
        return self

    def debug(self, _message):
        return None

    def info(self, _message):
        return None


class _FakeConnection:
    def __init__(self):
        self.client_listen_mode = "auto"
        self.just_woken_up = True
        self.logger = _FakeLogger()
        self.audio_states_reset = False

    def reset_audio_states(self):
        self.audio_states_reset = True


class ListenStartTest(unittest.TestCase):
    def test_start_ends_wakeup_audio_ignore_period(self):
        conn = _FakeConnection()

        asyncio.run(
            ListenTextMessageHandler().handle(
                conn,
                {"type": "listen", "state": "start", "mode": "realtime"},
            )
        )

        self.assertEqual(conn.client_listen_mode, "realtime")
        self.assertTrue(conn.audio_states_reset)
        self.assertFalse(conn.just_woken_up)


if __name__ == "__main__":
    unittest.main()
