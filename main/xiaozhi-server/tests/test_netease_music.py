import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from plugins_func.functions import play_netease_music as netease
from core.handle.intentHandler import handles_own_audio_response
from core.providers.tools.server_plugins.plugin_executor import (
    validate_mutually_exclusive_music_functions,
)
from core.utils.util import filter_sensitive_info


class _SelectionClient:
    def __init__(self, authenticated=False):
        self.is_authenticated = authenticated

    async def account_profile(self):
        if not self.is_authenticated:
            raise netease.NeteaseAuthenticationRequiredError("需要登录")
        return {"userId": 7}

    async def search_songs(self, _name):
        return [
            {"id": 1, "name": "目标歌曲", "fee": 1, "ar": [{"name": "歌手"}]},
            {"id": 2, "name": "目标歌曲", "fee": 8, "ar": [{"name": "歌手"}]},
        ]

    async def user_playlists(self):
        if not self.is_authenticated:
            raise netease.NeteaseAuthenticationRequiredError("需要登录")
        return [{"id": 8, "name": "我的民谣"}]

    async def playlist_tracks(self, _playlist_id):
        return [{"id": index, "name": f"歌曲{index}", "fee": 0} for index in range(5)]

    async def daily_songs(self):
        if not self.is_authenticated:
            raise netease.NeteaseAuthenticationRequiredError("需要登录")
        return [{"id": 11, "name": "每日歌曲", "fee": 1}]

    async def personal_fm(self):
        if not self.is_authenticated:
            raise netease.NeteaseAuthenticationRequiredError("需要登录")
        return [{"id": 12, "name": "私人FM歌曲", "fee": 1}]


class NeteaseMusicSelectionTest(unittest.IsolatedAsyncioTestCase):
    def test_tool_schema_exposes_playback_controls(self):
        actions = netease.play_netease_music_function_desc["function"]["parameters"][
            "properties"
        ]["action"]["enum"]

        self.assertTrue(
            {"next", "previous", "pause", "resume", "stop"}.issubset(actions)
        )

    async def test_anonymous_search_excludes_vip_song(self):
        tracks, _ = await netease._select_tracks(
            _SelectionClient(authenticated=False),
            "song",
            "目标歌曲",
            20,
        )

        self.assertEqual([track["id"] for track in tracks], [2])

    async def test_song_search_matches_artist_and_song_name(self):
        client = _SelectionClient(authenticated=True)
        client.search_songs = AsyncMock(
            return_value=[
                {"id": 1, "name": "晴天", "fee": 1, "ar": [{"name": "其他歌手"}]},
                {"id": 2, "name": "晴天", "fee": 1, "ar": [{"name": "周杰伦"}]},
            ]
        )

        tracks, _ = await netease._select_tracks(client, "song", "周杰伦 晴天", 20)

        self.assertEqual([track["id"] for track in tracks], [2])

    async def test_playlist_requires_login(self):
        with self.assertRaises(netease.NeteaseAuthenticationRequiredError):
            await netease._select_tracks(
                _SelectionClient(authenticated=False),
                "playlist",
                "我的民谣",
                20,
            )

    async def test_playlist_matches_name_and_respects_queue_limit(self):
        tracks, prompt = await netease._select_tracks(
            _SelectionClient(authenticated=True),
            "playlist",
            "民谣",
            3,
        )

        self.assertEqual(len(tracks), 3)
        self.assertIn("我的民谣", prompt)

    async def test_logged_in_daily_and_personal_fm_are_selected(self):
        client = _SelectionClient(authenticated=True)

        daily, daily_prompt = await netease._select_tracks(client, "daily", "", 20)
        personal_fm, fm_prompt = await netease._select_tracks(client, "personal_fm", "", 20)

        self.assertEqual(daily[0]["id"], 11)
        self.assertIn("每日推荐", daily_prompt)
        self.assertEqual(personal_fm[0]["id"], 12)
        self.assertIn("私人FM", fm_prompt)


class NeteaseMusicSecurityTest(unittest.TestCase):
    def test_cookie_is_redacted_inside_serialized_plugin_params(self):
        config = {
            "functions": [
                {"paramInfo": '{"cookie":"MUSIC_U=secret","quality":"standard"}'}
            ]
        }

        filtered = filter_sensitive_info(config)

        self.assertNotIn("MUSIC_U=secret", str(filtered))
        self.assertIn('"cookie": "***"', filtered["functions"][0]["paramInfo"])

    def test_song_id_cannot_escape_cache_directory(self):
        with self.assertRaises(netease.NeteaseMusicError):
            netease._safe_song_id("../outside")

    def test_runtime_rejects_multiple_music_plugins(self):
        with self.assertRaises(ValueError):
            validate_mutually_exclusive_music_functions(
                ["play_music", "play_netease_music"]
            )


class NeteaseMusicClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_anonymous_account_rejects_login_only_features(self):
        client = netease.NeteaseMusicClient({"api_base_url": "http://localhost:3000"})

        with self.assertRaises(netease.NeteaseAuthenticationRequiredError):
            await client.account_profile()

    async def test_anonymous_vip_song_is_rejected_before_request(self):
        client = netease.NeteaseMusicClient({"api_base_url": "http://localhost:3000"})
        client._request = AsyncMock()

        with self.assertRaises(netease.NeteaseMusicUnavailableError):
            await client.playable_url({"id": 1, "name": "会员歌曲", "fee": 1})

        client._request.assert_not_awaited()

    async def test_fee_eight_song_is_publicly_playable(self):
        client = netease.NeteaseMusicClient({"api_base_url": "http://localhost:3000"})
        client._request = AsyncMock(
            return_value={"data": [{"url": "https://m801.music.126.net/free.mp3", "freeTrialInfo": None}]}
        )

        url = await client.playable_url({"id": 8, "name": "公开歌曲", "fee": 8})

        self.assertEqual(url, "https://m801.music.126.net/free.mp3")

    async def test_logged_in_song_uses_account_rights_without_unblock(self):
        client = netease.NeteaseMusicClient(
            {"api_base_url": "http://localhost:3000", "cookie": "MUSIC_U=secret"}
        )
        client._request = AsyncMock(
            return_value={"data": [{"url": "https://m801.music.126.net/song.mp3", "freeTrialInfo": None}]}
        )

        url = await client.playable_url({"id": 7, "name": "会员歌曲", "fee": 1})

        self.assertEqual(url, "https://m801.music.126.net/song.mp3")
        _, params = client._request.await_args.args
        self.assertEqual(params["unblock"], "false")

    async def test_trial_url_is_not_accepted_as_full_playback(self):
        client = netease.NeteaseMusicClient(
            {"api_base_url": "http://localhost:3000", "cookie": "MUSIC_U=secret"}
        )
        client._request = AsyncMock(
            return_value={"data": [{"url": "https://audio.example/trial.mp3", "freeTrialInfo": {"end": 30}}]}
        )

        with self.assertRaises(netease.NeteaseMusicUnavailableError):
            await client.playable_url({"id": 7, "name": "会员歌曲", "fee": 1})

    async def test_replacement_source_is_rejected_even_when_api_unblock_is_enabled(self):
        client = netease.NeteaseMusicClient(
            {"api_base_url": "http://localhost:3000", "cookie": "MUSIC_U=secret"}
        )
        client._request = AsyncMock(
            return_value={"data": [{"url": "https://other-source.example/song.mp3", "freeTrialInfo": None}]}
        )

        with self.assertRaises(netease.NeteaseMusicUnavailableError):
            await client.playable_url({"id": 7, "name": "会员歌曲", "fee": 1})


class NeteaseMusicPreparationTest(unittest.IsolatedAsyncioTestCase):
    async def test_preparation_timeout_keeps_ready_tracks_and_cancels_slow_ones(self):
        class Client:
            async def playable_url(self, song):
                return f"https://audio.example/{song['id']}.mp3"

        class Cache:
            async def download(self, song, _url):
                if song["id"] == 2:
                    await asyncio.sleep(1)
                return Path(f"/cache/{song['id']}.mp3")

        resolved, errors = await netease._resolve_audio_files(
            Client(),
            Cache(),
            [{"id": 1, "name": "快歌"}, {"id": 2, "name": "慢歌"}],
            timeout_seconds=0.05,
        )

        self.assertEqual([song["id"] for song, _ in resolved], [1])
        self.assertEqual(errors, ["歌曲准备超时"])

    async def test_total_preparation_timeout_includes_catalog_requests(self):
        async def slow_selection(*_args):
            await asyncio.sleep(1)

        with patch.object(netease, "_select_tracks", side_effect=slow_selection):
            with self.assertRaises(netease.NeteaseMusicUnavailableError):
                await netease._prepare_playback(
                    object(), object(), "playlist", "慢歌单", 20, 0.01
                )

    async def test_total_timeout_keeps_tracks_ready_before_deadline(self):
        class Client:
            async def playable_url(self, song):
                return f"https://m801.music.126.net/{song['id']}.mp3"

        class Cache:
            async def download(self, song, _url):
                if song["id"] == 2:
                    await asyncio.sleep(1)
                return Path(f"/cache/{song['id']}.mp3")

        async def delayed_selection(*_args):
            await asyncio.sleep(0.02)
            return ([{"id": 1, "name": "快歌"}, {"id": 2, "name": "慢歌"}], "开始播放")

        with patch.object(netease, "_select_tracks", side_effect=delayed_selection):
            prompt, resolved, skipped = await netease._prepare_playback(
                Client(), Cache(), "playlist", "歌单", 20, 0.08
            )

        self.assertEqual(prompt, "开始播放")
        self.assertEqual([song["id"] for song, _path in resolved], [1])
        self.assertEqual(skipped, ["歌曲准备超时"])


class NeteaseMusicCacheTest(unittest.TestCase):
    def test_cache_reuses_recent_song_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "42.mp3"
            path.write_bytes(b"audio")
            cache = netease.NeteaseMusicCache(
                {"cache_dir": directory, "cache_ttl_hours": 1, "cache_size_mb": 64}
            )

            self.assertEqual(cache._existing(42), path)

    def test_cache_cleanup_keeps_active_partial_download(self):
        with tempfile.TemporaryDirectory() as directory:
            partial = Path(directory) / ".42.active.part"
            partial.write_bytes(b"partial")
            cache = netease.NeteaseMusicCache(
                {"cache_dir": directory, "cache_ttl_hours": 1, "cache_size_mb": 64}
            )
            cached = Path(directory) / "42.mp3"
            cached.write_bytes(b"audio")
            cache.max_cache_bytes = 0
            netease._CACHE_ACTIVE_PARTIALS.add(str(partial))

            try:
                cache.cleanup()
            finally:
                netease._CACHE_ACTIVE_PARTIALS.discard(str(partial))

            self.assertTrue(partial.exists())
            self.assertFalse(cached.exists())

    def test_cache_cleanup_keeps_file_already_reserved_for_playback(self):
        with tempfile.TemporaryDirectory() as directory:
            queued = Path(directory) / "42.mp3"
            queued.write_bytes(b"queued-audio")
            cache = netease.NeteaseMusicCache({"cache_dir": directory})
            cache.max_cache_bytes = 0
            cache.protect_for_playback([queued])
            cache.protect_for_playback([queued])

            cache.cleanup()

            self.assertTrue(queued.exists())

            netease.release_cache_file(queued)
            cache.cleanup()

            self.assertTrue(queued.exists())

            netease.release_cache_file(queued)
            cache.cleanup()

            self.assertFalse(queued.exists())

    def test_cache_reservation_counts_in_progress_downloads(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "42.mp3"
            cached.write_bytes(b"123456")
            cache = netease.NeteaseMusicCache({"cache_dir": directory})
            cache.max_cache_bytes = 10
            cache._protect(cached)

            with self.assertRaises(netease.NeteaseMusicError):
                cache._reserve_download(5)

    def test_saved_playback_queue_protects_file_until_connection_closes(self):
        with tempfile.TemporaryDirectory() as directory:
            queued = Path(directory) / "42.mp3"
            queued.write_bytes(b"queued-audio")
            cache = netease.NeteaseMusicCache({"cache_dir": directory})
            cache.max_cache_bytes = 0
            state = netease.NeteasePlaybackState(
                resolved=[({"id": 42, "name": "测试歌曲"}, queued)]
            )
            connection = _Connection()
            connection._netease_playback = state
            cache.protect_for_playback([queued])
            netease._protect_playback_state(state)

            cache.cleanup()
            self.assertTrue(queued.exists())
            self.assertIn(str(queued.resolve()), netease._CACHE_ACTIVE_REFERENCES)

            netease.close_netease_playback(connection)
            self.assertNotIn(str(queued.resolve()), netease._CACHE_ACTIVE_REFERENCES)
            cache.cleanup()
            self.assertFalse(queued.exists())


class NeteaseMusicDownloadTest(unittest.IsolatedAsyncioTestCase):
    async def test_download_rejects_redirected_replacement_source(self):
        class Response:
            url = "https://replacement.example/song.mp3"
            headers = {}

            async def __aenter__(self):
                return self

            async def __aexit__(self, *_args):
                return False

            def raise_for_status(self):
                return None

        class Client:
            async def __aenter__(self):
                return self

            async def __aexit__(self, *_args):
                return False

            def stream(self, *_args, **_kwargs):
                return Response()

        with tempfile.TemporaryDirectory() as directory:
            cache = netease.NeteaseMusicCache({"cache_dir": directory})
            with patch.object(netease.httpx, "AsyncClient", return_value=Client()):
                with self.assertRaises(netease.NeteaseMusicUnavailableError):
                    await cache.download(
                        {"id": 42, "name": "测试歌曲"},
                        "https://m801.music.126.net/song.mp3",
                    )


class _Queue:
    def __init__(self):
        self.items = []

    def put(self, item):
        self.items.append(item)


class _Tts:
    def __init__(self):
        self.tts_text_queue = _Queue()
        self.stored = []

    def store_tts_text(self, sentence_id, text):
        self.stored.append((sentence_id, text))


class _Connection:
    sentence_id = "sentence"
    intent_type = "intent_llm"

    def __init__(self):
        self.tts = _Tts()


class NeteaseMusicQueueTest(unittest.IsolatedAsyncioTestCase):
    async def test_playlist_schedules_one_track_at_a_time(self):
        connection = _Connection()
        resolved = [
            ({"id": 1, "name": "第一首"}, Path("/cache/1.mp3")),
            ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
        ]

        state = netease._start_playback(connection, "开始播放", resolved)
        await asyncio.sleep(0)

        messages = connection.tts.tts_text_queue.items
        file_messages = [message for message in messages if message.content_type == netease.ContentType.FILE]
        self.assertEqual([message.content_file for message in file_messages], ["/cache/1.mp3"])
        self.assertEqual(messages[0].sentence_type, netease.SentenceType.FIRST)
        self.assertEqual(
            connection.server_audio_playback_sentence_id,
            connection.sentence_id,
        )
        self.assertIsNotNone(file_messages[0].completion_event)

        file_messages[0].completion_event.set()
        await asyncio.sleep(0.15)

        file_messages = [
            message
            for message in messages
            if message.content_type == netease.ContentType.FILE
        ]
        self.assertEqual(
            [message.content_file for message in file_messages],
            ["/cache/1.mp3", "/cache/2.mp3"],
        )
        self.assertEqual(state.index, 1)
        netease.close_netease_playback(connection)

    async def test_next_restarts_from_next_saved_track(self):
        connection = _Connection()
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[
                ({"id": 1, "name": "第一首"}, Path("/cache/1.mp3")),
                ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
            ],
            index=0,
            status="paused",
        )

        response = netease._control_playback(connection, "next")
        await asyncio.sleep(0)

        self.assertEqual(response.response, "正在播放下一首，《第二首》")
        self.assertEqual(connection._netease_playback.index, 1)
        file_messages = [
            message
            for message in connection.tts.tts_text_queue.items
            if message.content_type == netease.ContentType.FILE
        ]
        self.assertEqual(file_messages[0].content_file, "/cache/2.mp3")
        netease.interrupt_netease_playback(connection)

    def test_pause_and_stop_keep_or_clear_saved_queue(self):
        connection = _Connection()
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[({"id": 1, "name": "第一首"}, Path("/cache/1.mp3"))],
            index=0,
            status="playing",
        )

        paused = netease._control_playback(connection, "pause")
        self.assertEqual(paused.response, "已暂停播放")
        self.assertEqual(connection._netease_playback.status, "paused")
        self.assertIsNone(connection.server_audio_playback_sentence_id)

        stopped = netease._control_playback(connection, "stop")
        self.assertEqual(stopped.response, "已停止播放")
        self.assertIsNone(connection._netease_playback)
        self.assertIsNone(connection.server_audio_playback_sentence_id)

    async def test_previous_and_resume_restart_saved_track(self):
        connection = _Connection()
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[
                ({"id": 1, "name": "第一首"}, Path("/cache/1.mp3")),
                ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
            ],
            index=1,
            status="paused",
        )

        previous = netease._control_playback(connection, "previous")
        await asyncio.sleep(0)
        self.assertEqual(previous.response, "正在播放上一首，《第一首》")
        self.assertEqual(connection._netease_playback.index, 0)

        netease.interrupt_netease_playback(connection)
        resumed = netease._control_playback(connection, "resume")
        await asyncio.sleep(0)
        self.assertEqual(resumed.response, "继续播放，《第一首》")
        self.assertEqual(connection._netease_playback.status, "playing")
        netease.interrupt_netease_playback(connection)

    def test_abort_pauses_but_keeps_saved_queue(self):
        connection = _Connection()
        state = netease.NeteasePlaybackState(
            resolved=[({"id": 1, "name": "第一首"}, Path("/cache/1.mp3"))],
            status="playing",
        )
        connection._netease_playback = state

        netease.interrupt_netease_playback(connection)

        self.assertIs(connection._netease_playback, state)
        self.assertEqual(state.status, "paused")

    def test_intent_handler_does_not_speak_server_music_response_twice(self):
        self.assertTrue(handles_own_audio_response("play_music"))
        self.assertTrue(handles_own_audio_response("play_netease_music"))
        self.assertFalse(handles_own_audio_response("hass_play_music"))


if __name__ == "__main__":
    unittest.main()
