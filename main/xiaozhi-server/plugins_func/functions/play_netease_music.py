import asyncio
import difflib
import os
import random
import re
import threading
import time
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING
from urllib.parse import urlparse

import httpx

from core.providers.tts.dto.dto import ContentType, SentenceType, TTSMessageDTO
from core.handle.sendAudioHandle import send_music_lyrics_event
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
ROLLING_PREFETCH_THRESHOLD = 3
LYRIC_FETCH_TIMEOUT_SECONDS = 3
MAX_LYRIC_LINES = 500
MAX_LYRIC_TEXT_CHARS = 32_000
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
_CACHE_PLAYBACK_STATE_REFERENCES = {}
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


def _protect_playback_state(state):
    protected_paths = []
    for _song, path in state.resolved:
        cache_path = Path(path).resolve()
        key = str(cache_path)
        lock = _cache_lock(cache_path.parent)
        with lock:
            _CACHE_PLAYBACK_STATE_REFERENCES[key] = (
                _CACHE_PLAYBACK_STATE_REFERENCES.get(key, 0) + 1
            )
        protected_paths.append(key)
    state.protected_paths = protected_paths


def _release_playback_state(state, release_queue_references=False):
    for key in state.protected_paths:
        cache_path = Path(key)
        lock = _CACHE_LOCKS.get(str(cache_path.parent))
        if lock is None:
            continue
        with lock:
            remaining = _CACHE_PLAYBACK_STATE_REFERENCES.get(key, 0) - 1
            if remaining > 0:
                _CACHE_PLAYBACK_STATE_REFERENCES[key] = remaining
            else:
                _CACHE_PLAYBACK_STATE_REFERENCES.pop(key, None)
    state.protected_paths = []
    if release_queue_references:
        for _song, path in state.resolved:
            release_cache_file(path)


class NeteaseMusicError(RuntimeError):
    """网易云音乐调用失败。"""


class NeteaseAuthenticationRequiredError(NeteaseMusicError):
    """当前操作需要网易云音乐登录。"""


class NeteaseMusicUnavailableError(NeteaseMusicError):
    """歌曲对当前账号不可播放。"""


class NeteaseManagerUnavailableError(NeteaseMusicError):
    """设备登录状态服务暂时不可用。"""


@dataclass
class NeteasePlaybackState:
    resolved: list
    index: int = 0
    status: str = "playing"
    generation: int = 0
    task: object = None
    protected_paths: list = field(default_factory=list)
    load_more: object = None
    prefetch_task: object = None
    source_exhausted: bool = False
    lyrics_loader: object = None


play_netease_music_function_desc = {
    "type": "function",
    "function": {
        "name": "play_netease_music",
        "description": (
            "通过网易云音乐播放歌曲。支持按歌名播放、播放当前登录账号的歌单、"
            "按歌手连续播放、我的收藏、每日推荐和私人FM，以及上一首、下一首、"
            "跳到指定序号、暂停、继续和停止。"
            "用户说‘播放某位歌手的歌’时必须使用artist，不要使用song。"
            "用户说‘播放我的收藏’、‘播放收藏’或‘播放我喜欢的音乐’时必须使用favorites。"
            "用户说‘播放第几首’或‘跳到第几首’时必须使用jump，并填写position。"
            "用户说‘下一首’、‘上一首’、‘暂停’、‘继续’或‘停止播放’时必须调用本工具。"
            "未登录时只能播放公开且无需会员的歌曲。"
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": [
                        "song",
                        "artist",
                        "favorites",
                        "playlist",
                        "daily",
                        "personal_fm",
                        "random",
                        "next",
                        "previous",
                        "jump",
                        "pause",
                        "resume",
                        "stop",
                    ],
                    "description": (
                        "播放类型：song按具体歌名播放；artist按歌手建立多首歌曲队列；"
                        "favorites播放账号的‘我喜欢的音乐’；"
                        "playlist播放用户歌单；"
                        "daily播放每日推荐；personal_fm播放私人FM；random随机播放；"
                        "next下一首；previous上一首；jump跳到队列指定序号；"
                        "pause暂停；resume继续；stop停止。"
                    ),
                },
                "name": {
                    "type": "string",
                    "description": (
                        "歌曲名、歌手名或歌单名。action为song、artist或playlist时填写；"
                        "action为favorites时填写空字符串；"
                        "用户没有提供名称时填写空字符串。"
                    ),
                },
                "position": {
                    "type": "integer",
                    "minimum": 1,
                    "description": (
                        "从 1 开始的播放队列序号，仅 action 为 jump 时填写。"
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


def _normalize_music_action(action, name):
    if action == "playlist" and str(name or "").strip() in {
        "我的收藏",
        "收藏",
        "我喜欢的音乐",
        "喜欢的音乐",
    }:
        return "favorites"
    return action


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


def _song_artist_names(song):
    return [
        str(artist.get("name") or "").strip()
        for artist in (song.get("ar") or song.get("artists") or [])
        if str(artist.get("name") or "").strip()
    ]


_LRC_TIMESTAMP_PATTERN = re.compile(
    r"\[(\d{1,3}):(\d{2})(?:[\.:](\d{1,3}))?\]"
)


def _parse_lrc_lyrics(value):
    parsed = []
    total_text_chars = 0
    for raw_line in str(value or "").splitlines():
        timestamps = list(_LRC_TIMESTAMP_PATTERN.finditer(raw_line))
        if not timestamps:
            continue
        text = _LRC_TIMESTAMP_PATTERN.sub("", raw_line).strip()
        if not text:
            continue
        text = text[:200]
        for timestamp in timestamps:
            minutes = int(timestamp.group(1))
            seconds = int(timestamp.group(2))
            if seconds >= 60:
                continue
            fraction = timestamp.group(3) or "0"
            milliseconds = int(fraction.ljust(3, "0")[:3])
            start_ms = (minutes * 60 + seconds) * 1000 + milliseconds
            if start_ms > 24 * 60 * 60 * 1000:
                continue
            if len(parsed) >= MAX_LYRIC_LINES:
                break
            if total_text_chars + len(text) > MAX_LYRIC_TEXT_CHARS:
                break
            parsed.append({"start_ms": start_ms, "text": text})
            total_text_chars += len(text)
        if (
            len(parsed) >= MAX_LYRIC_LINES
            or total_text_chars >= MAX_LYRIC_TEXT_CHARS
        ):
            break
    return sorted(parsed, key=lambda line: line["start_ms"])


def _build_lyrics_start_event(song, lyric_text):
    lines = _parse_lrc_lyrics(lyric_text)
    return {
        "version": 1,
        "action": "start",
        "playback_id": uuid.uuid4().hex,
        "track": {
            "id": _safe_song_id(song.get("id")),
            "title": str(song.get("name") or "未知歌曲").strip(),
            "artists": _song_artist_names(song),
        },
        "available": bool(lines),
        "lines": lines,
    }


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


def _songs_by_artist(artist_name, songs):
    normalized_artist = str(artist_name or "").strip().casefold()
    if not normalized_artist:
        return []
    matched = []
    seen_ids = set()
    for song in songs:
        artist_names = {
            str(artist.get("name") or "").strip().casefold()
            for artist in (song.get("ar") or song.get("artists") or [])
        }
        song_id = song.get("id")
        if normalized_artist in artist_names and song_id not in seen_ids:
            seen_ids.add(song_id)
            matched.append(song)
    return matched


def _artist_queue_prompt(artist_name, tracks):
    return (
        f"正在播放{artist_name}的歌曲，共 {len(tracks)} 首，"
        f"首先是《{_song_title(tracks[0])}》"
    )


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
        cache_key_params = {
            key: value
            for key, value in request_params.items()
            if key != "cookie"
        }
        query_string = str(httpx.QueryParams(cache_key_params))
        request_url = f"{self.base_url}{path}?{query_string}"

        try:
            async with httpx.AsyncClient(
                timeout=self.timeout,
                follow_redirects=False,
                trust_env=False,
            ) as client:
                response = await client.post(
                    request_url,
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
            raise NeteaseAuthenticationRequiredError("请先让当前设备扫码登录网易云音乐")
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

    async def lyrics(self, song_id):
        payload = await self._request("/lyric", {"id": _safe_song_id(song_id)})
        lyric = payload.get("lrc", {}).get("lyric")
        return lyric if isinstance(lyric, str) else ""

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
        if _CACHE_PLAYBACK_STATE_REFERENCES.get(key, 0) > 0:
            return True
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


async def _device_plugin_config(conn):
    """返回当前设备配置；旧的智能体 cookie 永远不会进入播放客户端。"""
    config = dict(_plugin_config(conn))
    config["cookie"] = ""
    manager_config = conn.config.get("manager-api", {})
    manager_url = str(manager_config.get("url") or "").strip().rstrip("/")
    manager_secret = str(manager_config.get("secret") or "").strip()
    device_id = str((conn.headers or {}).get("device-id") or "").strip()
    if not manager_url or not manager_secret or not device_id:
        raise NeteaseManagerUnavailableError("设备登录状态服务未配置")
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(5.0, connect=3.0),
            follow_redirects=False,
            trust_env=False,
        ) as client:
            response = await client.post(
                f"{manager_url}/config/netease-auth",
                headers={"Authorization": f"Bearer {manager_secret}"},
                json={"macAddress": device_id},
            )
            response.raise_for_status()
            payload = response.json()
    except (httpx.HTTPError, ValueError, TypeError) as exc:
        raise NeteaseManagerUnavailableError("设备登录状态服务暂时不可用") from exc

    data = payload.get("data") if isinstance(payload, dict) else None
    authorized = data.get("authorized") if isinstance(data, dict) else None
    if (not isinstance(payload, dict) or payload.get("code") != 0
            or not isinstance(data, dict) or not isinstance(authorized, bool)):
        raise NeteaseManagerUnavailableError("设备登录状态服务返回了无效数据")
    if authorized:
        credential = str(data.get("internalCredential") or "").strip()
        credential_version = data.get("credentialVersion")
        if not credential or not isinstance(credential_version, int):
            raise NeteaseManagerUnavailableError("设备登录状态服务返回了无效数据")
        config["cookie"] = credential
        config["_credential_version"] = credential_version
    return config


async def _invalidate_device_credential(conn, credential_version):
    if credential_version is None:
        return
    manager_config = conn.config.get("manager-api", {})
    manager_url = str(manager_config.get("url") or "").strip().rstrip("/")
    manager_secret = str(manager_config.get("secret") or "").strip()
    device_id = str((conn.headers or {}).get("device-id") or "").strip()
    if not manager_url or not manager_secret or not device_id:
        return
    try:
        async with httpx.AsyncClient(
            timeout=httpx.Timeout(5.0, connect=3.0),
            follow_redirects=False,
            trust_env=False,
        ) as client:
            response = await client.post(
                f"{manager_url}/config/netease-auth/invalidate",
                headers={"Authorization": f"Bearer {manager_secret}"},
                json={
                    "macAddress": device_id,
                    "credentialVersion": credential_version,
                },
            )
            response.raise_for_status()
            payload = response.json()
            if not isinstance(payload, dict) or payload.get("code") != 0:
                raise ValueError("invalid manager-api response")
    except (httpx.HTTPError, ValueError, TypeError):
        conn.logger.bind(tag=TAG).warning("设备网易云凭证失效回报失败")


async def _select_tracks(client, action, name, max_tracks):
    if client.is_authenticated:
        await client.account_profile()

    action = _normalize_music_action(action, name)

    if action == "song":
        if not str(name or "").strip():
            raise NeteaseMusicError("请告诉我想播放的歌曲名称")
        songs = await client.search_songs(name)
        if not client.is_authenticated:
            songs = [song for song in songs if _is_public_free_song(song)]
        normalized_name = str(name).strip().casefold()
        has_exact_song_name = any(
            str(song.get("name") or "").strip().casefold() == normalized_name
            for song in songs
        )
        artist_tracks = _songs_by_artist(name, songs)
        if artist_tracks and not has_exact_song_name:
            tracks = artist_tracks[:max_tracks]
            return tracks, _artist_queue_prompt(str(name).strip(), tracks)
        song = _best_song_match(name, songs)
        if not song:
            suffix = "，或先让当前设备扫码登录" if not client.is_authenticated else ""
            raise NeteaseMusicUnavailableError(f"没有找到可播放的《{name}》{suffix}")
        return [song], f"正在为您播放，《{_song_title(song)}》"

    if action == "artist":
        artist_name = str(name or "").strip()
        if not artist_name:
            raise NeteaseMusicError("请告诉我想播放的歌手名称")
        songs = await client.search_songs(
            artist_name,
            limit=min(max(max_tracks * 3, 20), 100),
        )
        if not client.is_authenticated:
            songs = [song for song in songs if _is_public_free_song(song)]
        tracks = _songs_by_artist(artist_name, songs)[:max_tracks]
        if not tracks:
            suffix = "，或先让当前设备扫码登录" if not client.is_authenticated else ""
            raise NeteaseMusicUnavailableError(
                f"没有找到歌手《{artist_name}》的可播放歌曲{suffix}"
            )
        return tracks, _artist_queue_prompt(artist_name, tracks)

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

    if action == "favorites":
        playlists = await client.user_playlists()
        favorites = next(
            (
                playlist
                for playlist in playlists
                if str(playlist.get("specialType")) == "5"
            ),
            None,
        )
        if favorites is None:
            favorites = next(
                (
                    playlist
                    for playlist in playlists
                    if "喜欢的音乐" in str(playlist.get("name") or "")
                ),
                None,
            )
        if favorites is None:
            raise NeteaseMusicUnavailableError(
                "当前账号中没有找到‘我喜欢的音乐’歌单"
            )
        tracks = await client.playlist_tracks(favorites.get("id"))
        if max_tracks is not None:
            tracks = tracks[:max_tracks]
        if not tracks:
            raise NeteaseMusicUnavailableError("我的收藏中暂时没有歌曲")
        return (
            tracks,
            f"正在播放我的收藏，共 {len(tracks)} 首，首先是《{_song_title(tracks[0])}》",
        )

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
    rolling=False,
):
    loop = asyncio.get_running_loop()
    deadline = loop.time() + timeout_seconds
    try:
        tracks, prompt = await asyncio.wait_for(
            _select_tracks(
                client,
                action,
                name,
                None if rolling else max_tracks,
            ),
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
    pending_tracks = tracks[max_tracks:] if rolling else []
    resolved, skipped = await _resolve_audio_files(
        client,
        cache,
        tracks[:max_tracks],
        remaining,
    )
    return prompt, resolved, skipped, pending_tracks


def _build_rolling_loader(client, cache, pending_tracks, batch_size, timeout_seconds):
    remaining_tracks = list(pending_tracks)

    async def load_more():
        while remaining_tracks:
            batch = remaining_tracks[:batch_size]
            try:
                resolved, _skipped = await _resolve_audio_files(
                    client,
                    cache,
                    batch,
                    timeout_seconds,
                )
            except NeteaseMusicUnavailableError:
                del remaining_tracks[: len(batch)]
                continue
            del remaining_tracks[: len(batch)]
            cache.protect_for_playback(path for _song, path in resolved)
            return resolved
        return []

    return load_more


def _cancel_playback_task(state):
    state.generation += 1
    task = state.task
    if task and not task.done():
        task.cancel()
    state.task = None
    prefetch_task = state.prefetch_task
    if prefetch_task and not prefetch_task.done():
        prefetch_task.cancel()
    state.prefetch_task = None


def interrupt_netease_playback(conn):
    state = getattr(conn, "_netease_playback", None)
    if not state:
        return
    _cancel_playback_task(state)
    conn.server_audio_playback_sentence_id = None
    if state.status == "playing":
        state.status = "paused"
    _schedule_lyrics_clear(conn, "interrupted")


def close_netease_playback(conn):
    state = getattr(conn, "_netease_playback", None)
    if not state:
        return
    _cancel_playback_task(state)
    _release_playback_state(state, release_queue_references=True)
    conn._netease_playback = None
    conn.server_audio_playback_sentence_id = None
    _schedule_lyrics_clear(conn, "closed")


def _clear_audio_queues(conn):
    clear_queues = getattr(conn, "clear_queues", None)
    if callable(clear_queues):
        clear_queues()


def _enqueue_track(
    conn,
    prompt,
    song,
    path,
    completion_event,
    start_session,
    playback_event=None,
):
    conn.tts.store_tts_text(conn.sentence_id, prompt)
    if start_session:
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.FIRST,
                content_type=ContentType.ACTION,
            )
        )
    if prompt:
        conn.tts.tts_text_queue.put(
            TTSMessageDTO(
                sentence_id=conn.sentence_id,
                sentence_type=SentenceType.MIDDLE,
                content_type=ContentType.TEXT,
                content_detail=prompt,
            )
        )
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=SentenceType.MIDDLE,
            content_type=ContentType.FILE,
            content_detail=_song_title(song),
            content_file=str(path),
            completion_event=completion_event,
            playback_event=playback_event,
        )
    )


def _schedule_lyrics_clear(conn, reason):
    if not getattr(conn, "features", {}).get("mcp"):
        return
    event = {"version": 1, "action": "clear", "reason": reason}
    loop = getattr(conn, "loop", None)
    if loop and loop.is_running():
        asyncio.run_coroutine_threadsafe(send_music_lyrics_event(conn, event), loop)


def _enqueue_playback_end(conn):
    conn.tts.tts_text_queue.put(
        TTSMessageDTO(
            sentence_id=conn.sentence_id,
            sentence_type=SentenceType.LAST,
            content_type=ContentType.ACTION,
        )
    )
    conn.server_audio_playback_sentence_id = None
    _schedule_lyrics_clear(conn, "completed")


def _protect_additional_tracks(state, resolved):
    for _song, path in resolved:
        cache_path = Path(path).resolve()
        key = str(cache_path)
        lock = _cache_lock(cache_path.parent)
        with lock:
            _CACHE_PLAYBACK_STATE_REFERENCES[key] = (
                _CACHE_PLAYBACK_STATE_REFERENCES.get(key, 0) + 1
            )
        state.protected_paths.append(key)


async def _prefetch_more(state):
    if state.source_exhausted or not state.load_more:
        return
    resolved = await state.load_more()
    if resolved:
        _protect_additional_tracks(state, resolved)
        state.resolved.extend(resolved)
    else:
        state.source_exhausted = True


def _schedule_prefetch(state):
    if state.source_exhausted or not state.load_more:
        return None
    if state.prefetch_task is None or state.prefetch_task.done():
        state.prefetch_task = asyncio.create_task(_prefetch_more(state))
    return state.prefetch_task


async def _run_playback(conn, state, generation, prompt):
    start_session = True
    try:
        while state.generation == generation and state.index < len(state.resolved):
            if len(state.resolved) - state.index <= ROLLING_PREFETCH_THRESHOLD:
                _schedule_prefetch(state)
            stop_event = getattr(conn, "stop_event", None)
            if stop_event is not None and stop_event.is_set():
                return
            song, path = state.resolved[state.index]
            lyric_text = ""
            if state.lyrics_loader:
                try:
                    lyric_text = await asyncio.wait_for(
                        state.lyrics_loader(song.get("id")),
                        timeout=LYRIC_FETCH_TIMEOUT_SECONDS,
                    )
                except (asyncio.TimeoutError, NeteaseMusicError) as exc:
                    conn.logger.bind(tag=TAG).warning(
                        f"获取《{_song_title(song)}》歌词失败: {exc}"
                    )
            playback_event = _build_lyrics_start_event(song, lyric_text)
            completion_event = threading.Event()
            _enqueue_track(
                conn,
                prompt if start_session else "",
                song,
                path,
                completion_event,
                start_session,
                playback_event,
            )
            prompt = ""
            start_session = False
            while not completion_event.is_set():
                await asyncio.sleep(0.1)
                if state.generation != generation:
                    return
                if stop_event is not None and stop_event.is_set():
                    return
            if state.index + 1 >= len(state.resolved):
                prefetch_task = state.prefetch_task
                if prefetch_task and not prefetch_task.done():
                    await prefetch_task
                if state.index + 1 < len(state.resolved):
                    state.index += 1
                    continue
                state.status = "stopped"
                _enqueue_playback_end(conn)
                return
            state.index += 1
    except asyncio.CancelledError:
        return


def _start_playback(
    conn,
    prompt,
    resolved,
    index=0,
    load_more=None,
    lyrics_loader=None,
):
    previous = getattr(conn, "_netease_playback", None)
    if previous:
        _clear_audio_queues(conn)
        _cancel_playback_task(previous)
        _release_playback_state(previous, release_queue_references=True)
    state = NeteasePlaybackState(
        resolved=list(resolved),
        index=index,
        status="playing",
        load_more=load_more,
        source_exhausted=load_more is None,
        lyrics_loader=lyrics_loader,
    )
    _protect_playback_state(state)
    conn._netease_playback = state
    conn.server_audio_playback_sentence_id = conn.sentence_id
    state.task = asyncio.create_task(
        _run_playback(conn, state, state.generation, prompt)
    )
    return state


def _restart_saved_playback(conn, state, prompt):
    _clear_audio_queues(conn)
    _cancel_playback_task(state)
    state.status = "playing"
    conn.server_audio_playback_sentence_id = conn.sentence_id
    state.task = asyncio.create_task(
        _run_playback(conn, state, state.generation, prompt)
    )


async def _control_playback(conn, action, position=0):
    state = getattr(conn, "_netease_playback", None)
    if not state or not state.resolved:
        return ActionResponse(
            action=Action.RESPONSE,
            result="当前没有网易云音乐播放队列",
            response="当前没有可控制的网易云音乐",
        )

    if action == "pause":
        _clear_audio_queues(conn)
        _cancel_playback_task(state)
        conn.server_audio_playback_sentence_id = None
        state.status = "paused"
        await send_music_lyrics_event(
            conn, {"version": 1, "action": "clear", "reason": "paused"}
        )
        return ActionResponse(
            action=Action.RESPONSE,
            result="已暂停播放",
            response="已暂停播放",
        )

    if action == "stop":
        _clear_audio_queues(conn)
        _cancel_playback_task(state)
        _release_playback_state(state, release_queue_references=True)
        conn._netease_playback = None
        conn.server_audio_playback_sentence_id = None
        await send_music_lyrics_event(
            conn, {"version": 1, "action": "clear", "reason": "stopped"}
        )
        return ActionResponse(
            action=Action.RESPONSE,
            result="已停止播放",
            response="已停止播放",
        )

    if action == "next":
        if len(state.resolved) - state.index <= ROLLING_PREFETCH_THRESHOLD:
            prefetch_task = _schedule_prefetch(state)
            if prefetch_task:
                await prefetch_task
        if state.index + 1 >= len(state.resolved):
            return ActionResponse(
                action=Action.RESPONSE,
                result="已经是最后一首",
                response="已经是最后一首了",
            )
        state.index += 1
        prompt = f"正在播放下一首，《{_song_title(state.resolved[state.index][0])}》"
        _restart_saved_playback(conn, state, prompt)
        return ActionResponse(action=Action.RECORD, result=prompt, response=prompt)

    if action == "jump":
        try:
            target_position = int(position)
        except (TypeError, ValueError):
            target_position = 0
        if target_position < 1:
            return ActionResponse(
                action=Action.RESPONSE,
                result="未提供有效的播放序号",
                response="请告诉我要跳到第几首",
            )

        target_index = target_position - 1
        while target_index >= len(state.resolved) and not state.source_exhausted:
            prefetch_task = _schedule_prefetch(state)
            if not prefetch_task:
                break
            await prefetch_task
        if target_index >= len(state.resolved):
            prompt = f"当前队列只有 {len(state.resolved)} 首"
            return ActionResponse(
                action=Action.RESPONSE,
                result=prompt,
                response=prompt,
            )

        state.index = target_index
        prompt = (
            f"正在播放第 {target_position} 首，"
            f"《{_song_title(state.resolved[target_index][0])}》"
        )
        _restart_saved_playback(conn, state, prompt)
        return ActionResponse(action=Action.RECORD, result=prompt, response=prompt)

    if action == "previous":
        if state.index <= 0:
            return ActionResponse(
                action=Action.RESPONSE,
                result="已经是第一首",
                response="已经是第一首了",
            )
        state.index -= 1
        prompt = f"正在播放上一首，《{_song_title(state.resolved[state.index][0])}》"
        _restart_saved_playback(conn, state, prompt)
        return ActionResponse(action=Action.RECORD, result=prompt, response=prompt)

    if action == "resume":
        song = state.resolved[state.index][0]
        prompt = f"继续播放，《{_song_title(song)}》"
        _restart_saved_playback(conn, state, prompt)
        return ActionResponse(action=Action.RECORD, result=prompt, response=prompt)

    raise NeteaseMusicError(f"不支持的播放控制: {action}")


@register_function(
    "play_netease_music",
    play_netease_music_function_desc,
    ToolType.SYSTEM_CTL,
)
async def play_netease_music(
    conn: "ConnectionHandler",
    action: str = "song",
    name: str = "",
    position: int = 0,
):
    config = None
    try:
        if action in {"next", "previous", "jump", "pause", "resume", "stop"}:
            return await _control_playback(conn, action, position=position)

        config = await _device_plugin_config(conn)
        action = _normalize_music_action(action, name)
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
        rolling = action == "favorites"
        prompt, resolved, skipped, pending_tracks = await _prepare_playback(
            client,
            cache,
            action,
            name,
            max_tracks,
            prepare_timeout,
            rolling=rolling,
        )
        if skipped:
            prompt += f"，其中 {len(skipped)} 首未能加入播放队列"
        cache.protect_for_playback(path for _song, path in resolved)
        load_more = None
        if pending_tracks:
            load_more = _build_rolling_loader(
                client,
                cache,
                pending_tracks,
                max_tracks,
                prepare_timeout,
            )
        _start_playback(
            conn,
            prompt,
            resolved,
            load_more=load_more,
            lyrics_loader=client.lyrics,
        )
        if skipped:
            conn.logger.bind(tag=TAG).warning(
                f"网易云播放队列跳过 {len(skipped)} 首不可播放歌曲: {'; '.join(skipped)}"
            )
        return ActionResponse(
            action=Action.RECORD,
            result=f"已加入 {len(resolved)} 首歌曲",
            response=prompt,
        )
    except NeteaseAuthenticationRequiredError:
        if isinstance(config, dict):
            await _invalidate_device_credential(conn, config.get("_credential_version"))
        message = "网易云音乐登录已失效，请让当前设备重新扫码登录"
        conn.logger.bind(tag=TAG).warning("设备网易云凭证已失效，已请求撤销")
        return ActionResponse(action=Action.RESPONSE, result=message, response=message)
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
