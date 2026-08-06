import asyncio
import difflib
import os
import random
import re
import threading
import time
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import urlparse

import httpx

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from plugins_func.register import Action, ActionResponse, ToolType, register_function

if TYPE_CHECKING:
    from core.connection import ConnectionHandler


TAG = __name__
TOPLIST_ID = "3778678"
DEFAULT_API_BASE_URL = "http://127.0.0.1:3000"
DEFAULT_MAX_TRACKS = 20
DEFAULT_CACHE_SIZE_MB = 512
DEFAULT_CACHE_TTL_HOURS = 24
DEFAULT_MAX_FILE_SIZE_MB = 40
DEFAULT_PREPARE_TIMEOUT_SECONDS = 20
PREPARE_FILE_PROTECTION_SECONDS = 60
QUEUE_FILE_PROTECTION_SECONDS = 12 * 3600
SUPPORTED_QUALITY_LEVELS = {
    "standard",
    "higher",
    "exhigh",
    "lossless",
    "hires",
}
PUBLIC_FREE_FEE_TYPES = {0, 8}
_CACHE_LOCKS = {}
_CACHE_PROTECTED_UNTIL = {}
_CACHE_ACTIVE_REFERENCES = {}
_CACHE_RESERVED_BYTES = {}
_CACHE_ACTIVE_PARTIALS = set()


def _cache_lock(cache_dir):
    return _CACHE_LOCKS.setdefault(str(cache_dir), threading.RLock())


def release_cache_file(path):
    if not path:
        return
    cache_path = Path(path).resolve()
    lock = _CACHE_LOCKS.get(str(cache_path.parent))
    if lock is None:
        return
    with lock:
        key = str(cache_path)
        references = _CACHE_ACTIVE_REFERENCES.get(key, [])
        if references:
            references.pop()
        if references:
            _CACHE_ACTIVE_REFERENCES[key] = references
        else:
            _CACHE_ACTIVE_REFERENCES.pop(key, None)
            _CACHE_PROTECTED_UNTIL.pop(key, None)


class NeteaseMusicError(RuntimeError):
    """网易云音乐调用失败。"""


class NeteaseAuthenticationRequiredError(NeteaseMusicError):
    """当前操作需要网易云音乐登录。"""


class NeteaseMusicUnavailableError(NeteaseMusicError):
    """歌曲对当前账号不可播放。"""


play_netease_music_function_desc = {
    "type": "function",
    "function": {
        "name": "play_netease_music",
        "description": (
            "通过网易云音乐播放歌曲。支持按歌名播放、播放当前登录账号的歌单、"
            "每日推荐和私人FM。未登录时只能播放公开且无需会员的歌曲。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["song", "playlist", "daily", "personal_fm", "random"],
                    "description": (
                        "播放类型：song按歌名播放；playlist播放用户歌单；"
                        "daily播放每日推荐；personal_fm播放私人FM；random随机播放。"
                    ),
                },
                "name": {
                    "type": "string",
                    "description": (
                        "歌曲名或歌单名。action为song或playlist时填写；"
                        "用户没有提供名称时填写空字符串。"
                    ),
                },
            },
            "required": ["action", "name"],
        },
    },
}


def _bounded_int(value, default, minimum, maximum):
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return default
    return max(minimum, min(parsed, maximum))


def _normalize_base_url(value):
    base_url = str(value or DEFAULT_API_BASE_URL).strip().rstrip("/")
    parsed = urlparse(base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise NeteaseMusicError("网易云音乐 API 地址必须是有效的 HTTP 或 HTTPS 地址")
    return base_url


def _song_artists(song):
    artists = song.get("ar") or song.get("artists") or []
    return "、".join(
        str(artist.get("name", "")).strip()
        for artist in artists
        if artist.get("name")
    )


def _song_title(song):
    name = str(song.get("name", "未知歌曲")).strip()
    artists = _song_artists(song)
    return f"{name} - {artists}" if artists else name


def _is_public_free_song(song):
    # fee=0 为完全免费歌曲，fee=8 为非会员可在线播放、会员才可下载的歌曲。
    return song.get("fee") in PUBLIC_FREE_FEE_TYPES


def _safe_song_id(value):
    song_id = str(value or "").strip()
    if not re.fullmatch(r"[1-9][0-9]*", song_id):
        raise NeteaseMusicError("网易云音乐服务返回了无效的歌曲 ID")
    return song_id


def _is_netease_audio_url(value):
    parsed = urlparse(str(value or ""))
    host = (parsed.hostname or "").lower()
    return parsed.scheme in {"http", "https"} and (
        host == "music.126.net"
        or host.endswith(".music.126.net")
        or host == "music.163.com"
        or host.endswith(".music.163.com")
    )


def _best_named_item(query, items, name_getter):
    normalized_query = str(query or "").strip().casefold()
    if not normalized_query:
        return None

    best_item = None
    best_score = 0.0
    for item in items:
        candidate = str(name_getter(item) or "").strip().casefold()
        if not candidate:
            continue
        if candidate == normalized_query:
            return item
        score = difflib.SequenceMatcher(None, normalized_query, candidate).ratio()
        if normalized_query in candidate:
            score += 0.25
        if score > best_score:
            best_item = item
            best_score = score
    return best_item if best_score >= 0.4 else None


def _best_song_match(query, songs):
    exact_name = _best_named_item(query, songs, lambda song: song.get("name", ""))
    normalized_query = str(query or "").strip().casefold()
    if exact_name and str(exact_name.get("name", "")).strip().casefold() == normalized_query:
        return exact_name
    return _best_named_item(
        query,
        songs,
        lambda song: " ".join(
            part for part in (song.get("name", ""), _song_artists(song)) if part
        ),
    ) or exact_name


class NeteaseMusicClient:
    def __init__(self, config):
        self.base_url = _normalize_base_url(config.get("api_base_url"))
        self.cookie = str(config.get("cookie") or "").strip()
        quality = str(config.get("quality") or "standard").strip().lower()
        self.quality = quality if quality in SUPPORTED_QUALITY_LEVELS else "standard"
        self.timeout = httpx.Timeout(20.0, connect=5.0)
        self._profile = None

    @property
    def is_authenticated(self):
        return bool(self.cookie)

    async def _request(self, path, params=None):
        request_params = dict(params or {})
        request_params["timestamp"] = int(time.time() * 1000)
        if self.cookie:
            request_params["cookie"] = self.cookie

        try:
            async with httpx.AsyncClient(
                timeout=self.timeout,
                follow_redirects=False,
                trust_env=False,
            ) as client:
                response = await client.post(
                    f"{self.base_url}{path}",
                    data=request_params,
                )
                response.raise_for_status()
        except httpx.TimeoutException as exc:
            raise NeteaseMusicError("网易云音乐服务响应超时") from exc
        except httpx.HTTPError as exc:
            raise NeteaseMusicError(f"网易云音乐服务请求失败: {exc}") from exc

        try:
            payload = response.json()
        except ValueError as exc:
            raise NeteaseMusicError("网易云音乐服务返回了无效数据") from exc
        if not isinstance(payload, dict):
            raise NeteaseMusicError("网易云音乐服务返回的数据格式不正确")

        code = payload.get("code")
        if code not in (None, 200):
            message = payload.get("message") or payload.get("msg") or f"错误码 {code}"
            if code in {301, 302, 400, 401}:
                raise NeteaseAuthenticationRequiredError(f"网易云音乐登录已失效: {message}")
            raise NeteaseMusicError(f"网易云音乐服务返回错误: {message}")
        return payload

    async def account_profile(self):
        if not self.is_authenticated:
            raise NeteaseAuthenticationRequiredError("请先在管理端扫码登录网易云音乐")
        if self._profile:
            return self._profile
        payload = await self._request("/user/account")
        profile = payload.get("profile")
        if not profile or not profile.get("userId"):
            raise NeteaseAuthenticationRequiredError("网易云音乐登录已失效，请重新扫码登录")
        self._profile = profile
        return self._profile

    async def search_songs(self, keywords, limit=20):
        payload = await self._request(
            "/cloudsearch",
            {"keywords": keywords, "type": 1, "limit": limit},
        )
        return payload.get("result", {}).get("songs") or []

    async def playlist_tracks(self, playlist_id, limit=1000):
        payload = await self._request(
            "/playlist/track/all",
            {"id": playlist_id, "limit": limit, "offset": 0},
        )
        return payload.get("songs") or []

    async def toplist_tracks(self):
        return await self.playlist_tracks(TOPLIST_ID, limit=200)

    async def user_playlists(self):
        profile = await self.account_profile()
        payload = await self._request(
            "/user/playlist",
            {"uid": profile["userId"], "limit": 1000, "offset": 0},
        )
        return payload.get("playlist") or []

    async def daily_songs(self):
        await self.account_profile()
        payload = await self._request("/recommend/songs")
        return payload.get("data", {}).get("dailySongs") or []

    async def personal_fm(self):
        await self.account_profile()
        payload = await self._request("/personal_fm")
        return payload.get("data") or []

    async def playable_url(self, song):
        if not self.is_authenticated and not _is_public_free_song(song):
            raise NeteaseMusicUnavailableError(
                f"《{_song_title(song)}》需要登录或会员权益，当前未登录"
            )

        payload = await self._request(
            "/song/url/v1",
            {
                "id": song.get("id"),
                "level": self.quality,
                "unblock": "false",
            },
        )
        data = (payload.get("data") or [{}])[0]
        if data.get("freeTrialInfo"):
            raise NeteaseMusicUnavailableError(
                f"《{_song_title(song)}》当前只能试听，未加入播放队列"
            )
        url = data.get("url")
        if not url:
            raise NeteaseMusicUnavailableError(
                f"《{_song_title(song)}》不在当前账号的可播放权益内"
            )
        if not _is_netease_audio_url(url):
            raise NeteaseMusicUnavailableError(
                f"《{_song_title(song)}》返回了非网易云音源，已拒绝播放"
            )
        return url


class NeteaseMusicCache:
    def __init__(self, config):
        self.cache_dir = Path(
            str(config.get("cache_dir") or "data/netease_music_cache")
        ).expanduser().resolve()
        self.cache_dir.mkdir(parents=True, exist_ok=True)
        self.max_cache_bytes = _bounded_int(
            config.get("cache_size_mb"), DEFAULT_CACHE_SIZE_MB, 64, 4096
        ) * 1024 * 1024
        self.ttl_seconds = _bounded_int(
            config.get("cache_ttl_hours"), DEFAULT_CACHE_TTL_HOURS, 1, 168
        ) * 3600
        self.max_file_bytes = _bounded_int(
            config.get("max_file_size_mb"), DEFAULT_MAX_FILE_SIZE_MB, 5, 100
        ) * 1024 * 1024
        self._lock = _cache_lock(self.cache_dir)

    def _protect(self, path, seconds=PREPARE_FILE_PROTECTION_SECONDS):
        _CACHE_PROTECTED_UNTIL[str(path)] = time.time() + seconds

    def protect_for_playback(self, paths):
        with self._lock:
            protection_seconds = max(
                self.ttl_seconds,
                QUEUE_FILE_PROTECTION_SECONDS,
            )
            for path in paths:
                key = str(Path(path).resolve())
                references = _CACHE_ACTIVE_REFERENCES.setdefault(key, [])
                references.append(time.time() + protection_seconds)

    def _is_protected(self, path):
        key = str(path)
        references = [
            expires_at
            for expires_at in _CACHE_ACTIVE_REFERENCES.get(key, [])
            if expires_at > time.time()
        ]
        if references:
            _CACHE_ACTIVE_REFERENCES[key] = references
            return True
        _CACHE_ACTIVE_REFERENCES.pop(key, None)
        protected_until = _CACHE_PROTECTED_UNTIL.get(key, 0)
        if protected_until <= time.time():
            _CACHE_PROTECTED_UNTIL.pop(key, None)
            return False
        return True

    def _cache_size(self):
        return sum(
            path.stat().st_size
            for path in self.cache_dir.iterdir()
            if path.is_file() and path.suffix != ".part"
        )

    def _reserve_download(self, size):
        with self._lock:
            self._cleanup_locked()
            cache_key = str(self.cache_dir)
            reserved = _CACHE_RESERVED_BYTES.get(cache_key, 0)
            if self._cache_size() + reserved + size > self.max_cache_bytes:
                raise NeteaseMusicError(
                    "网易云音乐缓存空间不足，无法准备更多歌曲"
                )
            _CACHE_RESERVED_BYTES[cache_key] = reserved + size

    def _release_download_reservation(self, size):
        if not size:
            return
        with self._lock:
            cache_key = str(self.cache_dir)
            remaining = max(0, _CACHE_RESERVED_BYTES.get(cache_key, 0) - size)
            if remaining:
                _CACHE_RESERVED_BYTES[cache_key] = remaining
            else:
                _CACHE_RESERVED_BYTES.pop(cache_key, None)

    def _existing(self, song_id):
        song_id = _safe_song_id(song_id)
        with self._lock:
            candidates = list(self.cache_dir.glob(f"{song_id}.*"))
            for candidate in candidates:
                if candidate.suffix == ".part" or not candidate.is_file():
                    continue
                if time.time() - candidate.stat().st_mtime <= self.ttl_seconds:
                    os.utime(candidate, None)
                    self._protect(candidate)
                    return candidate
                candidate.unlink(missing_ok=True)
        return None

    def cleanup(self):
        with self._lock:
            self._cleanup_locked()

    def _cleanup_locked(self):
        files = [path for path in self.cache_dir.iterdir() if path.is_file()]
        now = time.time()
        for path in files:
            age = now - path.stat().st_mtime
            if (
                path.suffix == ".part"
                and str(path) not in _CACHE_ACTIVE_PARTIALS
            ) or (
                path.suffix != ".part"
                and age > self.ttl_seconds
                and not self._is_protected(path)
            ):
                path.unlink(missing_ok=True)

        files = sorted(
            (
                path
                for path in self.cache_dir.iterdir()
                if path.is_file() and path.suffix != ".part"
            ),
            key=lambda path: path.stat().st_mtime,
        )
        total = sum(path.stat().st_size for path in files)
        for path in files:
            if total <= self.max_cache_bytes:
                break
            if self._is_protected(path):
                continue
            size = path.stat().st_size
            path.unlink(missing_ok=True)
            total -= size

    async def download(self, song, url):
        song_id = _safe_song_id(song.get("id"))
        existing = self._existing(song_id)
        if existing:
            return existing

        self.cleanup()
        suffix = Path(urlparse(url).path).suffix.lower()
        if suffix not in {".mp3", ".flac", ".wav", ".m4a", ".aac", ".ogg"}:
            suffix = ".mp3"
        target = self.cache_dir / f"{song_id}{suffix}"
        partial = self.cache_dir / f".{song_id}.{time.time_ns()}.part"
        reserved_bytes = 0
        with self._lock:
            _CACHE_ACTIVE_PARTIALS.add(str(partial))

        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(120.0, connect=10.0),
                follow_redirects=True,
                trust_env=False,
            ) as client:
                async with client.stream("GET", url) as response:
                    response.raise_for_status()
                    if not _is_netease_audio_url(response.url):
                        raise NeteaseMusicUnavailableError(
                            f"《{_song_title(song)}》重定向到了非网易云音源，已拒绝播放"
                        )
                    content_type = response.headers.get("content-type", "").lower()
                    if content_type and not (
                        content_type.startswith("audio/")
                        or "octet-stream" in content_type
                    ):
                        raise NeteaseMusicError(
                            f"《{_song_title(song)}》返回的不是音频文件"
                        )
                    content_length = response.headers.get("content-length")
                    declared_size = None
                    if content_length:
                        try:
                            declared_size = int(content_length)
                            if declared_size < 0:
                                raise ValueError
                            if declared_size > self.max_file_bytes:
                                raise NeteaseMusicError(
                                    f"《{_song_title(song)}》文件超过缓存大小限制"
                                )
                        except ValueError as exc:
                            raise NeteaseMusicError(
                                f"《{_song_title(song)}》返回了无效的文件大小"
                            ) from exc

                    reserved_bytes = declared_size or self.max_file_bytes
                    self._reserve_download(reserved_bytes)

                    downloaded = 0
                    with partial.open("wb") as output:
                        async for chunk in response.aiter_bytes(64 * 1024):
                            downloaded += len(chunk)
                            if downloaded > reserved_bytes:
                                raise NeteaseMusicError(
                                    f"《{_song_title(song)}》实际大小超过接口声明"
                                )
                            output.write(chunk)
            if downloaded == 0:
                raise NeteaseMusicError(f"《{_song_title(song)}》下载内容为空")
            with self._lock:
                os.replace(partial, target)
                self._protect(target)
                self._cleanup_locked()
                if self._cache_size() > self.max_cache_bytes:
                    _CACHE_PROTECTED_UNTIL.pop(str(target), None)
                    target.unlink(missing_ok=True)
                    raise NeteaseMusicError(
                        "网易云音乐缓存空间已被待播放歌曲占满，请稍后重试"
                    )
            return target
        except httpx.TimeoutException as exc:
            raise NeteaseMusicError(f"下载《{_song_title(song)}》超时") from exc
        except httpx.HTTPError as exc:
            raise NeteaseMusicError(f"下载《{_song_title(song)}》失败: {exc}") from exc
        except OSError as exc:
            raise NeteaseMusicError(
                f"缓存《{_song_title(song)}》时发生文件系统错误"
            ) from exc
        finally:
            self._release_download_reservation(reserved_bytes)
            with self._lock:
                _CACHE_ACTIVE_PARTIALS.discard(str(partial))
                partial.unlink(missing_ok=True)


def _plugin_config(conn):
    config = conn.config.get("plugins", {}).get("play_netease_music")
    if not isinstance(config, dict):
        raise NeteaseMusicError("网易云音乐插件配置不存在")
    return config


async def _select_tracks(client, action, name, max_tracks):
    if client.is_authenticated:
        await client.account_profile()

    if action == "song":
        if not str(name or "").strip():
            raise NeteaseMusicError("请告诉我想播放的歌曲名称")
        songs = await client.search_songs(name)
        if not client.is_authenticated:
            songs = [song for song in songs if _is_public_free_song(song)]
        song = _best_song_match(name, songs)
        if not song:
            suffix = "，或先在管理端扫码登录" if not client.is_authenticated else ""
            raise NeteaseMusicUnavailableError(f"没有找到可播放的《{name}》{suffix}")
        return [song], f"正在为您播放，《{_song_title(song)}》"

    if action == "playlist":
        if not str(name or "").strip():
            raise NeteaseMusicError("请告诉我想播放的歌单名称")
        playlists = await client.user_playlists()
        playlist = _best_named_item(name, playlists, lambda item: item.get("name"))
        if not playlist:
            raise NeteaseMusicUnavailableError(f"当前账号中没有找到歌单《{name}》")
        tracks = await client.playlist_tracks(playlist.get("id"))
        if not tracks:
            raise NeteaseMusicUnavailableError(f"歌单《{playlist.get('name')}》中没有歌曲")
        return tracks[:max_tracks], f"正在播放歌单，《{playlist.get('name')}》"

    if action == "daily":
        tracks = await client.daily_songs()
        if not tracks:
            raise NeteaseMusicUnavailableError("今天暂时没有可播放的每日推荐")
        return tracks[:max_tracks], "正在播放您的网易云每日推荐"

    if action == "personal_fm":
        tracks = await client.personal_fm()
        if not tracks:
            raise NeteaseMusicUnavailableError("私人FM暂时没有返回可播放歌曲")
        return tracks[:max_tracks], "正在播放您的网易云私人FM"

    if action == "random":
        tracks = await (
            client.daily_songs()
            if client.is_authenticated
            else client.toplist_tracks()
        )
        if not client.is_authenticated:
            tracks = [song for song in tracks if _is_public_free_song(song)]
        if not tracks:
            raise NeteaseMusicUnavailableError("当前没有可随机播放的歌曲")
        song = random.choice(tracks)
        return [song], f"正在为您随机播放，《{_song_title(song)}》"

    raise NeteaseMusicError(f"不支持的网易云音乐播放类型: {action}")


async def _resolve_audio_files(client, cache, tracks, timeout_seconds):
    resolved_by_index = {}
    errors = []
    semaphore = asyncio.Semaphore(3)

    async def resolve(index, song):
        async with semaphore:
            try:
                url = await client.playable_url(song)
                path = await cache.download(song, url)
                return index, song, path, None
            except NeteaseMusicError as exc:
                return index, song, None, exc

    tasks = [
        asyncio.create_task(resolve(index, song))
        for index, song in enumerate(tracks)
    ]
    try:
        done, pending = await asyncio.wait(tasks, timeout=timeout_seconds)
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
            errors.extend(["歌曲准备超时"] * len(pending))

        for task in done:
            index, song, path, error = task.result()
            if path:
                resolved_by_index[index] = (song, path)
            else:
                errors.append(f"{_song_title(song)}: {error}")
    finally:
        for task in tasks:
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    resolved = [resolved_by_index[index] for index in sorted(resolved_by_index)]
    if not resolved:
        detail = errors[0] if errors else "没有可播放的歌曲"
        raise NeteaseMusicUnavailableError(detail)
    return resolved, errors


async def _prepare_playback(
    client,
    cache,
    action,
    name,
    max_tracks,
    timeout_seconds,
):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout_seconds
    try:
        tracks, prompt = await asyncio.wait_for(
            _select_tracks(client, action, name, max_tracks),
            timeout=timeout_seconds,
        )
    except asyncio.TimeoutError as exc:
        raise NeteaseMusicUnavailableError(
            f"网易云音乐播放准备超过 {timeout_seconds} 秒，请稍后重试"
        ) from exc

    remaining = deadline - loop.time()
    if remaining <= 0:
        raise NeteaseMusicUnavailableError(
            f"网易云音乐播放准备超过 {timeout_seconds} 秒，请稍后重试"
        )
    resolved, skipped = await _resolve_audio_files(
        client,
        cache,
        tracks,
        remaining,
    )
    return prompt, resolved, skipped


def _enqueue_files(conn, prompt, resolved):
    conn.tts.store_tts_text(conn.sentence_id, prompt)
    if conn.intent_type == "intent_llm":
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=SentenceType.MIDDLE,
            content_type=ContentType.TEXT,
            content_detail=prompt,
        )
    )
    for song, path in resolved:
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.FILE,
                content_detail=_song_title(song),
                content_file=str(path),
            )
        )
    if conn.intent_type == "intent_llm":
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.LAST,
                content_type=ContentType.ACTION,
            )
        )


@register_function(
    "play_netease_music",
    play_netease_music_function_desc,
    ToolType.SYSTEM_CTL,
)
async def play_netease_music(
    conn: "ConnectionHandler",
    action: str = "song",
    name: str = "",
):
    try:
        config = _plugin_config(conn)
        client = NeteaseMusicClient(config)
        cache = NeteaseMusicCache(config)
        max_tracks = _bounded_int(
            config.get("max_tracks"), DEFAULT_MAX_TRACKS, 1, 50
        )
        prepare_timeout = _bounded_int(
            config.get("prepare_timeout_seconds"),
            DEFAULT_PREPARE_TIMEOUT_SECONDS,
            5,
            25,
        )
        prompt, resolved, skipped = await _prepare_playback(
            client,
            cache,
            action,
            name,
            max_tracks,
            prepare_timeout,
        )
        if skipped:
            prompt += f"，其中 {len(skipped)} 首未能加入播放队列"
        cache.protect_for_playback(path for _song, path in resolved)
        _enqueue_files(conn, prompt, resolved)
        if skipped:
            conn.logger.bind(tag=TAG).warning(
                f"网易云播放队列跳过 {len(skipped)} 首不可播放歌曲: {'; '.join(skipped)}"
            )
        return ActionResponse(
            action=Action.RECORD,
            result=f"已加入 {len(resolved)} 首歌曲",
            response=prompt,
        )
    except NeteaseAuthenticationRequiredError as exc:
        conn.logger.bind(tag=TAG).warning(f"网易云音乐需要重新登录: {exc}")
        return ActionResponse(action=Action.RESPONSE, result=str(exc), response=str(exc))
    except NeteaseMusicUnavailableError as exc:
        conn.logger.bind(tag=TAG).warning(f"网易云音乐不可播放: {exc}")
        return ActionResponse(action=Action.RESPONSE, result=str(exc), response=str(exc))
    except NeteaseMusicError as exc:
        conn.logger.bind(tag=TAG).error(f"网易云音乐播放失败: {exc}")
        return ActionResponse(
            action=Action.RESPONSE,
            result=str(exc),
            response="网易云音乐服务暂时不可用，请稍后再试",
        )
