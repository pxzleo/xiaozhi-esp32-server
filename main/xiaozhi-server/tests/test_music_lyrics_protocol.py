import asyncio
import json
import unittest
from unittest.mock import AsyncMock, Mock

from core.handle.sendAudioHandle import (
    queue_music_lyrics_event,
    send_music_lyrics_event,
)
from core.providers.tts.base import TTSProviderBase


class MusicLyricsProtocolTest(unittest.IsolatedAsyncioTestCase):
    async def test_lyrics_event_waits_behind_queued_prompt_audio(self):
        conn = Mock()
        conn.features = {"mcp": True}
        conn.sentence_id = "sentence"
        conn.audio_flow_control = {"sentence_id": "sentence"}
        conn.websocket.send = AsyncMock()
        controller = Mock()
        controller.pending_send_task.done.return_value = False
        conn.audio_rate_controller = controller

        task = asyncio.create_task(
            queue_music_lyrics_event(
                conn, {"version": 1, "action": "start", "playback_id": "p1"}
            )
        )
        await asyncio.sleep(0)

        conn.websocket.send.assert_not_awaited()
        queued_callback = controller.add_message.call_args.args[0]
        await queued_callback()
        await task
        conn.websocket.send.assert_awaited_once()

    def test_playback_event_is_queued_once_before_first_music_frame(self):
        order = []
        provider = Mock()
        provider._enqueue_playback_event.side_effect = lambda _message: order.append(
            "event"
        )
        message = Mock()
        callback = TTSProviderBase._playback_file_callback(
            provider,
            message,
            lambda _audio: order.append("audio"),
        )

        callback(b"first")
        callback(b"second")

        self.assertEqual(order, ["event", "audio", "audio"])

    async def test_wraps_lyrics_event_in_device_mcp_notification(self):
        conn = Mock()
        conn.features = {"mcp": True}
        conn.websocket.send = AsyncMock()
        event = {
            "version": 1,
            "action": "start",
            "playback_id": "playback-1",
            "track": {"id": "1", "title": "歌曲", "artists": ["歌手"]},
            "lines": [{"start_ms": 1000, "text": "歌词"}],
        }

        await send_music_lyrics_event(conn, event)

        payload = json.loads(conn.websocket.send.await_args.args[0])
        self.assertEqual(payload["type"], "mcp")
        self.assertEqual(
            payload["payload"]["method"],
            "notifications/netease_music/lyrics",
        )
        self.assertEqual(payload["payload"]["params"], event)


if __name__ == "__main__":
    unittest.main()
