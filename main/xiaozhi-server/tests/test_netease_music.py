import asyncio
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, Mock, patch

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
    def test_spoken_song_title_limits_artists_to_two(self):
        song = {
            "name": "合唱歌曲",
            "ar": [{"name": "歌手一"}, {"name": "歌手二"}, {"name": "歌手三"}],
        }

        self.assertEqual(
            netease._spoken_song_title(song),
            "合唱歌曲 - 歌手一、歌手二等",
        )
        self.assertEqual(
            netease._song_artist_names(song),
            ["歌手一", "歌手二", "歌手三"],
        )
        self.assertEqual(
            netease._spoken_song_title(
                {"name": "双人歌曲", "ar": song["ar"][:2]}
            ),
            "双人歌曲 - 歌手一、歌手二",
        )

    def test_parses_lrc_timestamps_for_client_playback_clock(self):
        lines = netease._parse_lrc_lyrics(
            "[ar:歌手]\n[00:01.20][00:03.450]第一句\n[00:05.000]第二句\n"
        )

        self.assertEqual(
            lines,
            [
                {"start_ms": 1200, "text": "第一句"},
                {"start_ms": 3450, "text": "第一句"},
                {"start_ms": 5000, "text": "第二句"},
            ],
        )

    async def test_client_fetches_standard_timeline_lyrics(self):
        client = netease.NeteaseMusicClient({"api_base_url": "http://localhost:3000"})
        client._request = AsyncMock(
            return_value={"lrc": {"lyric": "[00:01.000]第一句"}}
        )

        lyric = await client.lyrics(123)

        self.assertEqual(lyric, "[00:01.000]第一句")
        client._request.assert_awaited_once_with("/lyric", {"id": "123"})

    def test_tool_schema_exposes_playback_controls(self):
        properties = netease.play_netease_music_function_desc["function"]["parameters"][
            "properties"
        ]
        actions = properties["action"]["enum"]

        self.assertTrue(
            {"next", "previous", "jump", "pause", "resume", "stop"}.issubset(
                actions
            )
        )
        self.assertEqual(properties["position"]["type"], "integer")
        self.assertEqual(properties["position"]["minimum"], 1)

    def test_tool_schema_requires_random_playback_to_call_tool(self):
        description = netease.play_netease_music_function_desc["function"][
            "description"
        ]

        self.assertIn("随机播放", description)
        self.assertIn("必须调用本工具", description)
        self.assertIn("不得在未调用工具", description)
        self.assertIn("声称已经播放", description)


class NeteaseDeviceCredentialTest(unittest.IsolatedAsyncioTestCase):
    async def test_fetches_current_device_credential_and_overrides_legacy_cookie(self):
        conn = Mock()
        conn.config = {
            "plugins": {"play_netease_music": {"cookie": "legacy", "quality": "standard"}},
            "manager-api": {"url": "http://manager/xiaozhi", "secret": "server-secret"},
        }
        conn.headers = {"device-id": "11:22:33:44:55:66"}
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {
            "code": 0,
            "data": {
                "authorized": True,
                "internalCredential": "device-cookie",
                "credentialVersion": 7,
            },
        }
        with patch.object(netease.httpx, "AsyncClient") as client_class:
            client = client_class.return_value.__aenter__.return_value
            client.post = AsyncMock(return_value=response)
            config = await netease._device_plugin_config(conn)

        self.assertEqual(config["cookie"], "device-cookie")
        self.assertEqual(config["_credential_version"], 7)
        url = client.post.await_args.args[0]
        self.assertNotIn("device-cookie", url)
        self.assertNotIn("legacy", url)

    async def test_unconfigured_device_reports_manager_unavailable(self):
        conn = Mock()
        conn.config = {
            "plugins": {"play_netease_music": {"cookie": "legacy"}},
            "manager-api": {},
        }
        conn.headers = {"device-id": "11:22:33:44:55:66"}

        with self.assertRaises(netease.NeteaseManagerUnavailableError):
            await netease._device_plugin_config(conn)

    async def test_explicitly_logged_out_device_stays_anonymous(self):
        conn = Mock()
        conn.config = {
            "plugins": {"play_netease_music": {"cookie": "legacy"}},
            "manager-api": {"url": "http://manager", "secret": "secret"},
        }
        conn.headers = {"device-id": "11:22:33:44:55:66"}
        conn.config["manager-api"] = {"url": "http://manager", "secret": "secret"}
        response = Mock()
        response.raise_for_status.return_value = None
        response.json.return_value = {
            "code": 0,
            "data": {"authorized": False, "internalCredential": None},
        }
        with patch.object(netease.httpx, "AsyncClient") as client_class:
            client_class.return_value.__aenter__.return_value.post = AsyncMock(return_value=response)
            self.assertEqual((await netease._device_plugin_config(conn))["cookie"], "")

    async def test_manager_transport_and_payload_failures_are_not_anonymous(self):
        conn = Mock()
        conn.config = {
            "plugins": {"play_netease_music": {"cookie": "legacy"}},
            "manager-api": {"url": "http://manager", "secret": "secret"},
        }
        conn.headers = {"device-id": "11:22:33:44:55:66"}

        response_500 = Mock()
        response_500.raise_for_status.side_effect = netease.httpx.HTTPError("500")
        bad_json = Mock()
        bad_json.raise_for_status.return_value = None
        bad_json.json.side_effect = ValueError("bad json")
        non_object_json = Mock()
        non_object_json.raise_for_status.return_value = None
        non_object_json.json.return_value = []
        failures = [
            netease.httpx.TimeoutException("timeout"),
            response_500,
            bad_json,
            non_object_json,
        ]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__):
                with patch.object(netease.httpx, "AsyncClient") as client_class:
                    client = client_class.return_value.__aenter__.return_value
                    post = AsyncMock()
                    client.post = post
                    if isinstance(failure, Exception):
                        post.side_effect = failure
                    else:
                        post.return_value = failure
                    with self.assertRaises(netease.NeteaseManagerUnavailableError):
                        await netease._device_plugin_config(conn)

    async def test_authentication_failure_reports_the_exact_credential_version(self):
        conn = Mock()
        conn.logger.bind.return_value = conn.logger
        config = {
            "api_base_url": "http://localhost:3000",
            "cookie": "device-cookie",
            "_credential_version": 9,
        }
        with (
            patch.object(netease, "_device_plugin_config", AsyncMock(return_value=config)),
            patch.object(netease, "NeteaseMusicCache"),
            patch.object(
                netease,
                "_prepare_playback",
                AsyncMock(side_effect=netease.NeteaseAuthenticationRequiredError("expired")),
            ),
            patch.object(netease, "_invalidate_device_credential", AsyncMock()) as invalidate,
        ):
            response = await netease.play_netease_music(conn, action="song", name="测试")

        invalidate.assert_awaited_once_with(conn, 9)
        self.assertIn("重新扫码登录", response.response)

    def test_tool_schema_exposes_artist_queue(self):
        actions = netease.play_netease_music_function_desc["function"]["parameters"][
            "properties"
        ]["action"]["enum"]

        self.assertIn("artist", actions)

    def test_tool_schema_exposes_favorites_queue(self):
        actions = netease.play_netease_music_function_desc["function"]["parameters"][
            "properties"
        ]["action"]["enum"]

        self.assertIn("favorites", actions)

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

    async def test_artist_search_builds_multi_song_queue_for_matching_artist(self):
        client = _SelectionClient(authenticated=True)
        client.search_songs = AsyncMock(
            return_value=[
                {"id": 1, "name": "十年", "fee": 1, "ar": [{"name": "陈奕迅"}]},
                {"id": 2, "name": "富士山下", "fee": 1, "ar": [{"name": "陈奕迅"}]},
                {"id": 3, "name": "十年", "fee": 1, "ar": [{"name": "其他歌手"}]},
                {"id": 4, "name": "K歌之王", "fee": 1, "ar": [{"name": "陈奕迅"}]},
            ]
        )

        tracks, prompt = await netease._select_tracks(
            client, "artist", "陈奕迅", 2
        )

        self.assertEqual([track["id"] for track in tracks], [1, 2])
        self.assertIn("陈奕迅", prompt)
        self.assertIn("2 首", prompt)

    async def test_song_action_with_artist_only_name_also_builds_artist_queue(self):
        client = _SelectionClient(authenticated=True)
        client.search_songs = AsyncMock(
            return_value=[
                {"id": 1, "name": "十年", "fee": 1, "ar": [{"name": "陈奕迅"}]},
                {"id": 2, "name": "富士山下", "fee": 1, "ar": [{"name": "陈奕迅"}]},
            ]
        )

        tracks, _prompt = await netease._select_tracks(
            client, "song", "陈奕迅", 20
        )

        self.assertEqual([track["id"] for track in tracks], [1, 2])

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

    async def test_favorites_uses_account_liked_music_playlist(self):
        client = _SelectionClient(authenticated=True)
        client.user_playlists = AsyncMock(
            return_value=[
                {"id": 7, "name": "其他歌单", "specialType": 0},
                {"id": 8, "name": "用户的喜欢的音乐", "specialType": 5},
            ]
        )
        client.playlist_tracks = AsyncMock(
            return_value=[
                {"id": 1, "name": "收藏歌曲1", "fee": 1},
                {"id": 2, "name": "收藏歌曲2", "fee": 1},
                {"id": 3, "name": "收藏歌曲3", "fee": 1},
            ]
        )

        tracks, prompt = await netease._select_tracks(
            client, "favorites", "", 2
        )

        self.assertEqual([track["id"] for track in tracks], [1, 2])
        self.assertIn("我的收藏", prompt)
        client.playlist_tracks.assert_awaited_once_with(8)

    async def test_my_favorites_playlist_alias_uses_favorites_queue(self):
        client = _SelectionClient(authenticated=True)
        client.user_playlists = AsyncMock(
            return_value=[
                {"id": 8, "name": "用户的喜欢的音乐", "specialType": 5}
            ]
        )
        client.playlist_tracks = AsyncMock(
            return_value=[{"id": 1, "name": "收藏歌曲", "fee": 1}]
        )

        tracks, _prompt = await netease._select_tracks(
            client, "playlist", "我的收藏", 20
        )

        self.assertEqual([track["id"] for track in tracks], [1])

    async def test_logged_in_daily_and_personal_fm_are_selected(self):
        client = _SelectionClient(authenticated=True)

        daily, daily_prompt = await netease._select_tracks(client, "daily", "", 20)
        personal_fm, fm_prompt = await netease._select_tracks(client, "personal_fm", "", 20)

        self.assertEqual(daily[0]["id"], 11)
        self.assertIn("每日推荐", daily_prompt)
        self.assertEqual(personal_fm[0]["id"], 12)
        self.assertIn("私人FM", fm_prompt)

    async def test_random_builds_shuffled_candidate_pool(self):
        client = _SelectionClient(authenticated=False)
        client.toplist_tracks = AsyncMock(
            return_value=[
                {"id": index, "name": f"随机歌曲{index}", "fee": 0}
                for index in range(1, 6)
            ]
        )

        with patch.object(netease.random, "shuffle") as shuffle:
            tracks, prompt = await netease._select_tracks(
                client, "random", "", 3
            )

        shuffle.assert_called_once()
        self.assertEqual([track["id"] for track in tracks], [1, 2, 3, 4, 5])
        self.assertEqual(prompt, "正在随机播放")

    async def test_user_input_error_is_spoken_verbatim(self):
        conn = Mock()
        conn.logger.bind.return_value = conn.logger
        with (
            patch.object(
                netease,
                "_device_plugin_config",
                AsyncMock(return_value={"max_tracks": 20}),
            ),
            patch.object(netease, "NeteaseMusicClient"),
            patch.object(netease, "NeteaseMusicCache"),
            patch.object(
                netease,
                "_prepare_playback",
                AsyncMock(side_effect=netease.NeteaseMusicError("请告诉我想播放的歌曲名称")),
            ),
        ):
            response = await netease.play_netease_music(
                conn, action="song", name=""
            )

        self.assertEqual(response.response, "请告诉我想播放的歌曲名称")

    async def test_random_does_not_announce_replaced_candidates(self):
        conn = Mock()
        conn.logger.bind.return_value = conn.logger
        cache = Mock()
        resolved = [
            ({"id": index, "name": f"歌曲{index}"}, Path(f"/cache/{index}.mp3"))
            for index in range(20)
        ]
        with (
            patch.object(
                netease,
                "_device_plugin_config",
                AsyncMock(return_value={"max_tracks": 20}),
            ),
            patch.object(netease, "NeteaseMusicClient"),
            patch.object(netease, "NeteaseMusicCache", return_value=cache),
            patch.object(
                netease,
                "_prepare_playback",
                AsyncMock(
                    return_value=(
                        "随机播放 20 首，先播《歌曲0》",
                        resolved,
                        ["候选一不可播放", "候选二不可播放"],
                        [],
                    )
                ),
            ),
            patch.object(netease, "_start_playback"),
        ):
            response = await netease.play_netease_music(
                conn, action="random", name=""
            )

        self.assertEqual(response.response, "随机播放 20 首，先播《歌曲0》")
        self.assertNotIn("未能加入", response.response)


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
    async def test_request_adds_timestamp_to_url_to_bypass_api_cache(self):
        client = netease.NeteaseMusicClient(
            {"api_base_url": "http://localhost:3000"}
        )
        response = AsyncMock()
        response.raise_for_status = lambda: None
        response.json = lambda: {"code": 200, "result": {}}
        http_client = AsyncMock()
        http_client.__aenter__.return_value = http_client
        http_client.post.return_value = response

        with patch.object(
            netease.httpx, "AsyncClient", return_value=http_client
        ), patch.object(netease.time, "time", return_value=1234.567):
            await client._request("/cloudsearch", {"keywords": "陈奕迅"})

        request_url = http_client.post.await_args.args[0]
        self.assertEqual(
            request_url,
            "http://localhost:3000/cloudsearch?keywords=%E9%99%88%E5%A5%95%E8%BF%85&timestamp=1234567",
        )

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
    async def test_random_replaces_unplayable_candidate_before_reaching_limit(self):
        class Client:
            async def playable_url(self, song):
                if song["id"] == 2:
                    raise netease.NeteaseMusicUnavailableError("不可播放")
                return f"https://m801.music.126.net/{song['id']}.mp3"

        class Cache:
            async def download(self, song, _url):
                return Path(f"/cache/{song['id']}.mp3")

        tracks = [
            {"id": 1, "name": "第一首"},
            {"id": 2, "name": "失效歌曲"},
            {"id": 3, "name": "候补歌曲"},
        ]
        with patch.object(
            netease,
            "_select_tracks",
            new=AsyncMock(return_value=(tracks, "正在随机播放")),
        ):
            prompt, resolved, skipped, pending = await netease._prepare_playback(
                Client(), Cache(), "random", "", 2, 1
            )

        self.assertEqual([song["id"] for song, _path in resolved], [1, 3])
        self.assertEqual(pending, [])
        self.assertEqual(len(skipped), 1)
        self.assertIn("2 首", prompt)

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
            prompt, resolved, skipped, pending = await netease._prepare_playback(
                Client(), Cache(), "playlist", "歌单", 20, 0.08
            )

        self.assertEqual(prompt, "开始播放")
        self.assertEqual([song["id"] for song, _path in resolved], [1])
        self.assertEqual(skipped, ["歌曲准备超时"])
        self.assertEqual(pending, [])

    async def test_rolling_preparation_keeps_later_tracks_for_next_batch(self):
        class Client:
            async def playable_url(self, song):
                return f"https://m801.music.126.net/{song['id']}.mp3"

        class Cache:
            async def download(self, song, _url):
                return Path(f"/cache/{song['id']}.mp3")

        tracks = [{"id": index, "name": f"歌曲{index}"} for index in range(5)]
        with patch.object(
            netease,
            "_select_tracks",
            new=AsyncMock(return_value=(tracks, "开始播放")),
        ):
            _prompt, resolved, _skipped, pending = await netease._prepare_playback(
                Client(), Cache(), "favorites", "", 2, 1, rolling=True
            )

        self.assertEqual([song["id"] for song, _path in resolved], [0, 1])
        self.assertEqual([song["id"] for song in pending], [2, 3, 4])

    async def test_rolling_loader_skips_failed_batch_and_tries_later_tracks(self):
        cache = Mock()
        loader = netease._build_rolling_loader(
            Mock(),
            cache,
            [{"id": 1}, {"id": 2}, {"id": 3}],
            batch_size=2,
            timeout_seconds=1,
        )
        resolved = [({"id": 3}, Path("/cache/3.mp3"))]

        with patch.object(
            netease,
            "_resolve_audio_files",
            new=AsyncMock(
                side_effect=[
                    netease.NeteaseMusicUnavailableError("前一批均不可播放"),
                    (resolved, []),
                ]
            ),
        ) as resolve_audio_files:
            loaded = await loader()

        self.assertEqual(loaded, resolved)
        self.assertEqual(resolve_audio_files.await_count, 2)
        cache.protect_for_playback.assert_called_once()

    async def test_cancelled_rolling_loader_retries_the_same_batch(self):
        cache = Mock()
        loader = netease._build_rolling_loader(
            Mock(),
            cache,
            [{"id": 1}, {"id": 2}, {"id": 3}],
            batch_size=2,
            timeout_seconds=1,
        )
        started = asyncio.Event()

        async def wait_until_cancelled(*_args):
            started.set()
            await asyncio.Event().wait()

        with patch.object(
            netease,
            "_resolve_audio_files",
            new=AsyncMock(side_effect=wait_until_cancelled),
        ):
            task = asyncio.create_task(loader())
            await started.wait()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task

        resolved = [({"id": 1}, Path("/cache/1.mp3"))]
        with patch.object(
            netease,
            "_resolve_audio_files",
            new=AsyncMock(return_value=(resolved, [])),
        ) as resolve_audio_files:
            loaded = await loader()

        self.assertEqual(loaded, resolved)
        retried_batch = resolve_audio_files.await_args.args[2]
        self.assertEqual([song["id"] for song in retried_batch], [1, 2])


class NeteaseMusicCacheTest(unittest.TestCase):
    def test_cache_reuses_recent_song_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "42.mp3"
            path.write_bytes(b"audio")
            cache = netease.NeteaseMusicCache(
                {"cache_dir": directory, "cache_ttl_hours": 1, "cache_size_mb": 64}
            )

            self.assertEqual(cache._existing(42), path)

    def test_cache_reuses_expired_file_while_playback_protects_it(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "42.mp3"
            path.write_bytes(b"audio")
            expired = path.stat().st_mtime - 7200
            netease.os.utime(path, (expired, expired))
            cache = netease.NeteaseMusicCache(
                {"cache_dir": directory, "cache_ttl_hours": 1}
            )
            cache.protect_for_playback([path])

            try:
                self.assertEqual(cache._existing(42), path)
                self.assertTrue(path.exists())
            finally:
                netease.release_cache_file(path)

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

    def test_cache_reservation_evicts_oldest_file_to_make_room(self):
        with tempfile.TemporaryDirectory() as directory:
            oldest = Path(directory) / "1.mp3"
            newest = Path(directory) / "2.mp3"
            oldest.write_bytes(b"12345")
            newest.write_bytes(b"6789")
            oldest.touch()
            newest.touch()
            oldest_mtime = oldest.stat().st_mtime - 10
            netease.os.utime(oldest, (oldest_mtime, oldest_mtime))
            cache = netease.NeteaseMusicCache({"cache_dir": directory})
            cache.max_cache_bytes = 10

            cache._reserve_download(5)
            try:
                self.assertFalse(oldest.exists())
                self.assertTrue(newest.exists())
            finally:
                cache._release_download_reservation(5)

    def test_cache_reservations_share_capacity_and_reject_oversized_total(self):
        with tempfile.TemporaryDirectory() as directory:
            cached = Path(directory) / "1.mp3"
            cached.write_bytes(b"123")
            cache = netease.NeteaseMusicCache({"cache_dir": directory})
            cache.max_cache_bytes = 10

            cache._reserve_download(4)
            cache._reserve_download(4)
            try:
                self.assertFalse(cached.exists())
                with self.assertRaises(netease.NeteaseMusicError):
                    cache._reserve_download(3)
            finally:
                cache._release_download_reservation(4)
                cache._release_download_reservation(4)

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
    async def test_track_queue_contains_timed_lyrics_start_event(self):
        connection = _Connection()
        lyrics_loader = AsyncMock(
            return_value="[00:01.000]第一句\n[00:05.000]第二句"
        )
        state = netease._start_playback(
            connection,
            "开始播放",
            [
                (
                    {
                        "id": 1,
                        "name": "测试歌曲",
                        "ar": [{"name": "测试歌手"}],
                    },
                    Path("/cache/1.mp3"),
                )
            ],
            lyrics_loader=lyrics_loader,
        )
        await asyncio.sleep(0.01)

        file_message = next(
            message
            for message in connection.tts.tts_text_queue.items
            if message.content_type == netease.ContentType.FILE
        )
        event = file_message.playback_event
        self.assertEqual(event["action"], "start")
        self.assertEqual(event["track"]["id"], "1")
        self.assertEqual(event["track"]["title"], "测试歌曲")
        self.assertEqual(event["track"]["artists"], ["测试歌手"])
        self.assertEqual(event["lines"][0], {"start_ms": 1000, "text": "第一句"})
        self.assertTrue(event["playback_id"])
        lyrics_loader.assert_awaited_once_with(1)
        netease.close_netease_playback(connection)

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

        response = await netease._control_playback(connection, "next")
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

    async def test_next_at_batch_boundary_loads_and_plays_next_batch(self):
        connection = _Connection()
        load_more = AsyncMock(
            return_value=[
                ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
                ({"id": 3, "name": "第三首"}, Path("/cache/3.mp3")),
            ]
        )
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[({"id": 1, "name": "第一首"}, Path("/cache/1.mp3"))],
            index=0,
            status="paused",
            load_more=load_more,
        )

        response = await netease._control_playback(connection, "next")
        await asyncio.sleep(0)

        self.assertEqual(response.response, "正在播放下一首，《第二首》")
        self.assertEqual(connection._netease_playback.index, 1)
        self.assertEqual(len(connection._netease_playback.resolved), 3)
        load_more.assert_awaited_once()
        netease.interrupt_netease_playback(connection)

    async def test_jump_uses_one_based_queue_position(self):
        connection = _Connection()
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[
                ({"id": 1, "name": "第一首"}, Path("/cache/1.mp3")),
                ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
                ({"id": 3, "name": "第三首"}, Path("/cache/3.mp3")),
            ],
            index=0,
            status="paused",
        )

        response = await netease._control_playback(connection, "jump", position=3)
        await asyncio.sleep(0)

        self.assertEqual(response.response, "正在播放第 3 首，《第三首》")
        self.assertEqual(connection._netease_playback.index, 2)
        file_messages = [
            message
            for message in connection.tts.tts_text_queue.items
            if message.content_type == netease.ContentType.FILE
        ]
        self.assertEqual(file_messages[0].content_file, "/cache/3.mp3")
        netease.interrupt_netease_playback(connection)

    async def test_jump_loads_enough_rolling_batches(self):
        connection = _Connection()
        load_more = AsyncMock(
            side_effect=[
                [
                    ({"id": 3, "name": "第三首"}, Path("/cache/3.mp3")),
                    ({"id": 4, "name": "第四首"}, Path("/cache/4.mp3")),
                ],
                [
                    ({"id": 5, "name": "第五首"}, Path("/cache/5.mp3")),
                    ({"id": 6, "name": "第六首"}, Path("/cache/6.mp3")),
                ],
            ]
        )
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[
                ({"id": 1, "name": "第一首"}, Path("/cache/1.mp3")),
                ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
            ],
            index=0,
            status="paused",
            load_more=load_more,
        )

        response = await netease._control_playback(connection, "jump", position=5)
        await asyncio.sleep(0)

        self.assertEqual(response.response, "正在播放第 5 首，《第五首》")
        self.assertEqual(connection._netease_playback.index, 4)
        self.assertEqual(load_more.await_count, 2)
        netease.interrupt_netease_playback(connection)

    async def test_jump_past_end_reports_actual_queue_length(self):
        connection = _Connection()
        load_more = AsyncMock(return_value=[])
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[
                ({"id": 1, "name": "第一首"}, Path("/cache/1.mp3")),
                ({"id": 2, "name": "第二首"}, Path("/cache/2.mp3")),
            ],
            index=0,
            status="paused",
            load_more=load_more,
        )

        response = await netease._control_playback(connection, "jump", position=3)

        self.assertEqual(response.response, "当前队列只有 2 首")
        self.assertEqual(connection._netease_playback.index, 0)
        load_more.assert_awaited_once()

    async def test_pause_and_stop_keep_or_clear_saved_queue(self):
        connection = _Connection()
        connection.features = {"mcp": True}
        connection.websocket = Mock()
        connection.websocket.send = AsyncMock()
        connection._netease_playback = netease.NeteasePlaybackState(
            resolved=[({"id": 1, "name": "第一首"}, Path("/cache/1.mp3"))],
            index=0,
            status="playing",
        )

        paused = await netease._control_playback(connection, "pause")
        self.assertEqual(paused.response, "已暂停播放")
        self.assertEqual(connection._netease_playback.status, "paused")
        self.assertIsNone(connection.server_audio_playback_sentence_id)
        pause_event = connection.websocket.send.await_args.args[0]
        self.assertIn('"action": "clear"', pause_event)
        self.assertIn('"reason": "paused"', pause_event)

        stopped = await netease._control_playback(connection, "stop")
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

        previous = await netease._control_playback(connection, "previous")
        await asyncio.sleep(0)
        self.assertEqual(previous.response, "正在播放上一首，《第一首》")
        self.assertEqual(connection._netease_playback.index, 0)

        netease.interrupt_netease_playback(connection)
        resumed = await netease._control_playback(connection, "resume")
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
