import asyncio
import hashlib
import json
import os
import re
import socket
import time
import unicodedata
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlsplit, urlunsplit

import httpx

from config.logger import setup_logging
from config.manage_api_client import (
    ManageApiError,
    claim_proactive_monitor_tasks,
    complete_proactive_monitor_task,
    create_proactive_monitor_event,
    evaluate_proactive_news_candidates,
    get_proactive_monitor_event,
)
from plugins_func.functions.get_news_from_newsnow import CHANNEL_MAP
from plugins_func.functions.get_weather import HEADERS

TAG = __name__
logger = setup_logging()
POLL_SECONDS = 30
MAX_TASKS = 20
MAX_WEATHER_FINGERPRINTS = 128
MAX_NEWS_FINGERPRINTS = 32
SOURCE_TIMEOUT = httpx.Timeout(10.0, connect=3.0)
_SEVERITY_RANK = {"minor": 1, "moderate": 2, "severe": 3, "extreme": 4}
_SEVERITY_CODE = {"minor": "m", "moderate": "o", "severe": "s", "extreme": "e"}
_CODE_SEVERITY = {value: key for key, value in _SEVERITY_CODE.items()}
_HAZARD_RANK = {"normal": 1, "high": 2, "critical": 3}
_WEATHER_CODES = {
    "rainstorm": {"308", "310", "311", "312", "316", "317", "318"},
    "thunderstorm": {"302", "303", "304"},
    "hail": {"304"},
    "blizzard": {"403", "410"},
}
_EXCLUDED_NEWS = re.compile(
    r"明星|综艺|娱乐|演唱会|票房|恋情|离婚|网红|游戏|电竞|足球|篮球|NBA|世界杯|比赛|夺冠|热梗|穿搭",
    re.IGNORECASE,
)
_MAJOR_NEWS = re.compile(
    r"地震|海啸|洪水|台风|暴雨|山火|爆炸|坍塌|事故|遇难|伤亡|失联|疏散|撤离|预警|"
    r"国务院|全国人大|央行|重大政策|紧急状态|制裁|停火|开战|冲突|袭击|导弹|核|政变|"
    r"金融危机|系统性风险|熔断|破产|芯片禁令|人工智能监管|重大突破",
    re.IGNORECASE,
)
_SOURCE_WEIGHT = {"澎湃新闻": 4, "财联社": 4, "参考消息": 4, "联合早报": 3, "百度热搜": 1}


class MonitorError(RuntimeError):
    def __init__(self, code, message, *, retryable=False):
        self.code = code
        self.retryable = retryable
        super().__init__(message)


def _utc_now():
    return datetime.now(timezone.utc)


def _iso(value):
    return value.astimezone(timezone.utc).isoformat(timespec="seconds")


def _parse_time(value):
    if not isinstance(value, str) or not value.strip():
        return None
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _number(value, *, default=None):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return default
    return number if number == number and abs(number) != float("inf") else default


def _optional_number(value, *, default=0):
    if value is None or value == "":
        return default
    number = _number(value)
    if number is None:
        raise MonitorError("weather_hourly_invalid", "逐小时天气可空数值字段无效")
    return number


def _trim(value, limit):
    return re.sub(r"\s+", " ", str(value or "")).strip()[:limit]


def _trim_utf8(value, max_bytes):
    raw = str(value or "").encode("utf-8")[:max_bytes]
    return raw.decode("utf-8", errors="ignore").strip()


def _bounded_event_payload(payload):
    result = dict(payload)
    for field in ("message", "title", "action"):
        while len(json.dumps(
            {key: value for key, value in result.items() if key != "reference_url"},
            ensure_ascii=False, separators=(",", ":"),
        ).encode("utf-8")) > 512 and field in result:
            value = result[field]
            if len(value.encode("utf-8")) <= 12:
                break
            result[field] = _trim_utf8(value, len(value.encode("utf-8")) - 12)
    size = len(json.dumps(
        {key: value for key, value in result.items() if key != "reference_url"},
        ensure_ascii=False, separators=(",", ":"),
    ).encode("utf-8"))
    if size > 512 or any(not value for value in result.values()):
        raise MonitorError("event_payload_too_large", "外界事件内容超过持久化上限")
    return result


def _hash(*parts, size=40):
    raw = "\x1f".join(str(part) for part in parts)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:size]


def _append_fingerprint(state, fingerprint, max_items=MAX_NEWS_FINGERPRINTS):
    values = [item for item in state.get("fingerprints", []) if isinstance(item, str)]
    if fingerprint not in values:
        values.append(fingerprint)
    return values[-max_items:]


def _event(task, *, topic, priority, event_type, reason, payload, dedupe_key,
           requires_response, expires_hours, stable_time=None,
           dedupe_policy="event_id", dedupe_window_hours=None, event_identity=None):
    now = _utc_now()
    created = stable_time if isinstance(stable_time, datetime) else now.replace(
        minute=0, second=0, microsecond=0
    )
    if created.tzinfo is None:
        created = created.replace(tzinfo=timezone.utc)
    created = created.astimezone(timezone.utc)
    event_id = "ext-" + _hash(
        task["device_id"], event_identity or dedupe_key, size=56
    )
    event = {
        "mac_address": task["mac_address"],
        "event_id": event_id,
        "topic": topic,
        "priority": priority,
        "reason": reason,
        "event_type": event_type,
        "payload": _bounded_event_payload(payload),
        "created_at": int(created.timestamp() * 1000),
        "expires_at": int((created + timedelta(hours=expires_hours)).timestamp() * 1000),
        "dedupe_key": dedupe_key[:128],
        "dedupe_policy": dedupe_policy,
        "requires_response": requires_response,
    }
    if dedupe_window_hours is not None:
        event["dedupe_window_hours"] = dedupe_window_hours
    return event


async def _create_external_event_idempotently(event):
    try:
        return await create_proactive_monitor_event(event)
    except Exception as create_error:
        try:
            existing = await get_proactive_monitor_event(
                event["event_id"], event["mac_address"]
            )
        except Exception:
            raise create_error
        payload = existing.get("payload") if isinstance(existing, dict) else None
        if (
            not isinstance(existing, dict)
            or existing.get("event_id") != event["event_id"]
            or existing.get("mac_address") != event["mac_address"]
            or existing.get("topic") != event["topic"]
            or existing.get("event_type") != event["event_type"]
            or existing.get("dedupe_key") != event["dedupe_key"]
            or not isinstance(payload, dict)
            or payload.get("reference_id") != event["payload"].get("reference_id")
        ):
            raise create_error
        return existing


class QWeatherClient:
    def __init__(self, _config=None):
        self._city_cache = {}

    def _access(self, task):
        if task.get("weather_credentials_error"):
            raise MonitorError(task["weather_credentials_error"], "和风天气凭据配置错误")
        host = task.get("weather_api_host")
        auth_type = task.get("weather_auth_type")
        credential = task.get("weather_credential")
        if (
            not isinstance(host, str) or not host.startswith("https://")
            or auth_type not in {"api_key", "bearer"}
            or not isinstance(credential, str) or not credential
        ):
            raise MonitorError("weather_credentials_invalid", "和风天气凭据无效")
        parsed = urlsplit(host)
        normalized_host = (parsed.hostname or "").lower()
        if (
            parsed.scheme != "https" or not normalized_host
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path not in {"", "/"}
            or parsed.port not in {None, 443}
            or normalized_host == "localhost"
            or normalized_host.endswith((".localhost", ".local", ".internal"))
            or "." not in normalized_host
            or ":" in normalized_host
            or re.fullmatch(r"[0-9.]+", normalized_host)
        ):
            raise MonitorError("weather_api_host_invalid", "和风天气API Host无效")
        if len(credential) > 4096 or "\r" in credential or "\n" in credential:
            raise MonitorError("weather_credentials_invalid", "和风天气凭据无效")
        headers = dict(HEADERS)
        if auth_type == "api_key":
            headers["X-QW-Api-Key"] = credential
        else:
            headers["Authorization"] = f"Bearer {credential}"
        return host.rstrip("/"), headers

    async def _get(self, base_url, headers, path, **params):
        current_alert_api = path.startswith("/weatheralert/")
        try:
            async with httpx.AsyncClient(timeout=SOURCE_TIMEOUT, trust_env=False) as client:
                response = await client.get(
                    f"{base_url}{path}", params=params, headers=headers
                )
                response.raise_for_status()
                data = response.json()
        except httpx.TimeoutException as error:
            raise MonitorError("weather_timeout", "和风天气请求超时", retryable=True) from error
        except httpx.HTTPStatusError as error:
            status = error.response.status_code
            if status in {401, 403}:
                raise MonitorError("weather_api_denied", "和风天气接口凭据无效或权限不足") from error
            raise MonitorError(
                "weather_request_failed", "和风天气请求失败",
                retryable=status == 429 or status >= 500,
            ) from error
        except (httpx.HTTPError, ValueError) as error:
            raise MonitorError("weather_request_failed", "和风天气请求失败", retryable=True) from error
        success = isinstance(data, dict) and (
            str(data.get("code")) == "200"
            or current_alert_api and isinstance(data.get("metadata"), dict)
        )
        if not success:
            raise MonitorError("weather_api_denied", "和风天气接口无权限或返回错误")
        return data

    async def resolve_location(self, location, base_url, headers):
        if not isinstance(location, str) or not location.strip():
            raise MonitorError("weather_location_missing", "天气地点缺失")
        key = location.strip()
        cache_key = (base_url, key)
        cached = self._city_cache.get(cache_key)
        if cached and cached[0] > time.monotonic():
            return cached[1]
        data = await self._get(
            base_url, headers, "/geo/v2/city/lookup", location=key, lang="zh"
        )
        choices = data.get("location")
        if not isinstance(choices, list) or not choices or not isinstance(choices[0], dict):
            raise MonitorError("weather_location_unresolved", "天气城市解析失败")
        city = choices[0]
        location_id = _trim(city.get("id"), 160)
        if not location_id:
            raise MonitorError("weather_location_unresolved", "天气城市缺少ID")
        latitude = _number(city.get("lat"))
        longitude = _number(city.get("lon"))
        if latitude is None or longitude is None:
            raise MonitorError("weather_location_unresolved", "天气城市缺少坐标")
        result = {
            "id": location_id,
            "name": _trim(city.get("name") or key, 40),
            "latitude": latitude,
            "longitude": longitude,
        }
        self._city_cache[cache_key] = (time.monotonic() + 3600, result)
        return result

    async def snapshot(self, task, location):
        base_url, headers = self._access(task)
        city = await self.resolve_location(location, base_url, headers)
        warning, hourly = await asyncio.gather(
            self._get(
                base_url, headers,
                f"/weatheralert/v1/current/{city['latitude']:.2f}/{city['longitude']:.2f}",
                localTime="false", lang="zh",
            ),
            self._get(
                base_url, headers, "/v7/weather/24h", location=city["id"], lang="zh"
            ),
        )
        warning_items = warning.get("alerts", warning.get("warning", []))
        hourly_items = hourly.get("hourly", [])
        if not isinstance(warning_items, list) or not isinstance(hourly_items, list) or not hourly_items:
            raise MonitorError("weather_response_invalid", "和风天气结构化响应无效")
        return city, warning_items, hourly_items


def _warning_is_active(item, now):
    message_type_value = item.get("messageType")
    if not isinstance(message_type_value, dict):
        raise MonitorError("weather_warning_invalid", "官方天气预警messageType结构无效")
    message_type = str(
        message_type_value.get("code") or ""
    ).lower()
    if not message_type:
        raise MonitorError("weather_warning_invalid", "官方天气预警缺少messageType")
    if message_type in {"cancel", "cancelled", "canceled", "expired"}:
        return False
    effective_value = item.get("effectiveTime") or item.get("effective") or item.get("startTime")
    expires_value = item.get("expireTime") or item.get("expire") or item.get("endTime")
    effective_missing = effective_value is None or effective_value == ""
    issued_value = item.get("issuedTime")
    effective = _parse_time(issued_value) if effective_missing else _parse_time(effective_value)
    expires = _parse_time(expires_value)
    if not effective_missing and effective is None:
        raise MonitorError("weather_warning_invalid", "官方天气预警生效时间无效")
    if effective_missing and issued_value is not None and issued_value != "" and effective is None:
        raise MonitorError("weather_warning_invalid", "官方天气预警发布时间无效")
    if expires is None:
        raise MonitorError("weather_warning_invalid", "官方天气预警有效期无效")
    return (effective is None or effective <= now) and expires > now


def _normalized_hourly(items):
    result = []
    for item in items[:24]:
        if not isinstance(item, dict):
            raise MonitorError("weather_hourly_invalid", "逐小时天气条目结构无效")
        when = _parse_time(item.get("fxTime"))
        temp = _number(item.get("temp"))
        icon = _trim(item.get("icon"), 64)
        wind = _number(item.get("windSpeed"))
        precip = _optional_number(item.get("precip"))
        pop = _optional_number(item.get("pop"))
        if (
            when is None or temp is None or not icon or wind is None
            or wind < 0 or precip is None or precip < 0
            or pop is None or not 0 <= pop <= 100
        ):
            raise MonitorError("weather_hourly_invalid", "逐小时天气必填字段无效")
        normalized = {
            "forecast_time": _iso(when),
            "weather_code": icon,
            "wind_speed_kmh": wind,
            "precip_mm": precip,
            "pop_pct": int(pop),
            "temp_c": temp,
        }
        result.append(normalized)
    if not result:
        raise MonitorError("weather_hourly_invalid", "逐小时天气数据为空")
    return result


def _validate_monitor_state(monitor_type, state):
    allowed = {"schema_version", "fingerprints", "detection_status"}
    if monitor_type == "weather":
        allowed.add("baseline")
    if not isinstance(state, dict) or set(state) - allowed or state.get("schema_version") != 1:
        raise MonitorError("monitor_state_invalid", "监测状态结构无效")
    fingerprints = state.get("fingerprints")
    status = state.get("detection_status")
    if (
        not isinstance(fingerprints, list)
        or any(not isinstance(value, str) or not value or len(value) > 160 for value in fingerprints)
        or not isinstance(status, dict)
        or set(status) - {
            "last_event_at", "cooldown_until", "active_warning_ids", "active_hazards",
            "last_cluster_id",
        }
    ):
        raise MonitorError("monitor_state_invalid", "监测状态字段无效")
    encoded = json.dumps(state, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(encoded) > 4096:
        raise MonitorError("monitor_state_too_large", "监测状态超过4096字节")
    return state


def detect_weather_hazards(hourly, config):
    hours = int(config.get("forecast_hours", 6))
    near = hourly[:hours]
    enabled = set(config.get("hazard_types") or _WEATHER_CODES.keys() | {
        "high_wind", "high_temperature", "low_temperature", "temperature_drop"
    })
    hazards = []

    def add(kind, severity, selected):
        if kind not in enabled or not selected:
            return
        hazards.append({
            "type": kind,
            "severity": severity,
            "window_start": selected[0]["forecast_time"],
            "window_end": selected[-1]["forecast_time"],
        })

    for kind, codes in _WEATHER_CODES.items():
        matched = [entry for entry in near if entry["weather_code"] in codes]
        if kind == "rainstorm":
            matched += [entry for entry in near if entry["pop_pct"] >= int(config.get("precip_probability", 70))]
            matched = list({entry["forecast_time"]: entry for entry in matched}.values())
            matched.sort(key=lambda entry: entry["forecast_time"])
        add(kind, "high", matched)
    add("high_wind", "high", [e for e in near if e["wind_speed_kmh"] >= int(config.get("wind_speed_kmh", 62))])
    add("high_temperature", "high", [
        e for e in near if e.get("temp_c") is not None
        and e["temp_c"] >= int(config.get("high_temp_c", 35))
    ])
    add("low_temperature", "high", [
        e for e in near if e.get("temp_c") is not None
        and e["temp_c"] <= int(config.get("low_temp_c", 0))
    ])
    if "temperature_drop" in enabled and len(hourly) >= 2:
        temperatures = [entry for entry in hourly[:24] if entry.get("temp_c") is not None]
        threshold = int(config.get("temp_drop_24h_c", 8))
        if len(temperatures) >= 2:
            start = temperatures[0]["temp_c"]
            matched = [entry for entry in temperatures[1:] if start - entry["temp_c"] >= threshold]
            add("temperature_drop", "high", [temperatures[0], matched[-1]] if matched else [])
    unique = {}
    for hazard in hazards:
        old = unique.get(hazard["type"])
        if old is None or _HAZARD_RANK[hazard["severity"]] > _HAZARD_RANK[old["severity"]]:
            unique[hazard["type"]] = hazard
    return list(unique.values())


def _weather_text(city, kind, source=None):
    actions = {
        "rainstorm": "请减少非必要出行，注意积水和地质灾害风险。",
        "thunderstorm": "请尽快进入室内并远离高处和金属设施。",
        "hail": "请进入坚固建筑并保护车辆和室外物品。",
        "blizzard": "请减少出行，注意道路结冰并做好保暖。",
        "high_wind": "请远离临时搭建物和高空坠物风险。",
        "high_temperature": "请注意补水，避免长时间户外活动。",
        "low_temperature": "请及时添衣，并留意道路结冰。",
        "temperature_drop": "请及时添衣，关注老人和儿童保暖。",
    }
    label = source or kind.replace("_", " ")
    return _trim(f"{city}出现{label}风险。{actions.get(kind, '请关注官方信息并调整出行安排。')}", 300)


async def process_weather(task, client):
    location = task.get("weather_location")
    if task.get("weather_location_error"):
        raise MonitorError(task["weather_location_error"], "天气地点配置错误")
    city, raw_warnings, raw_hourly = await client.snapshot(task, location)
    config = task.get("config") if isinstance(task.get("config"), dict) else {}
    prior = task.get("state") if isinstance(task.get("state"), dict) else {}
    prior_baseline = prior.get("baseline") if isinstance(prior.get("baseline"), dict) else None
    if (
        prior_baseline is not None
        and prior_baseline.get("location_id") != city["id"]
    ):
        prior = {}
    now = _utc_now()
    hourly = _normalized_hourly(raw_hourly)
    hazards = detect_weather_hazards(hourly, config)
    prior_status = prior.get("detection_status") if isinstance(prior.get("detection_status"), dict) else {}
    prior_warning_tokens = set(prior_status.get("active_warning_ids") or [])
    prior_hazards = set(prior_status.get("active_hazards") or [])
    fingerprints = list(prior.get("fingerprints") or [])
    active_warnings = []
    warning_events = []
    minimum = str(config.get("minimum_warning_severity", "moderate"))
    minimum_rank = _SEVERITY_RANK.get(minimum, 2)
    for item in raw_warnings:
        if not isinstance(item, dict):
            raise MonitorError("weather_warning_invalid", "官方天气预警条目结构无效")
        severity = str(item.get("severity") or "").lower()
        if severity not in _SEVERITY_RANK:
            raise MonitorError("weather_warning_invalid", "官方天气预警severity无效")
        if not _warning_is_active(item, now):
            continue
        warning_id = _trim(item.get("id"), 160)
        if not warning_id:
            raise MonitorError("weather_warning_invalid", "官方天气预警ID无效")
        warning_hash = _hash(warning_id, size=12)
        warning_token = f"{warning_hash}:{_SEVERITY_CODE[severity]}"
        active_warnings.append(warning_token)
        if _SEVERITY_RANK[severity] < minimum_rank:
            continue
        fingerprint = f"w:{warning_token}"
        first_baseline = not isinstance(prior.get("baseline"), dict)
        initial_critical = first_baseline and severity in {"severe", "extreme"}
        if fingerprint in fingerprints:
            continue
        prior_ranks = [
            _SEVERITY_RANK.get(_CODE_SEVERITY.get(token.rsplit(":", 1)[-1]), 0)
            for token in prior_warning_tokens
            if isinstance(token, str) and token.startswith(warning_hash + ":")
        ]
        if prior_ranks and max(prior_ranks) >= _SEVERITY_RANK[severity]:
            continue
        if not prior_ranks and first_baseline and not initial_critical:
            fingerprints = _append_fingerprint(
                {"fingerprints": fingerprints}, fingerprint, MAX_WEATHER_FINGERPRINTS
            )
            continue
        priority = "critical" if severity in {"severe", "extreme"} else "high"
        event_type_value = item.get("eventType")
        event_name = (
            event_type_value.get("name") if isinstance(event_type_value, dict) else None
        )
        title = _trim(
            item.get("headline") or item.get("title") or item.get("typeName")
            or event_name or "官方天气预警",
            100,
        )
        official_text = _trim(
            "。".join(
                value for value in (
                    item.get("description") or item.get("text"), item.get("instruction")
                ) if isinstance(value, str) and value.strip()
            ),
            240,
        )
        message = _trim(
            (official_text + "。" if official_text else f"{city['name']}已发布{title}。")
            + "请关注官方预警，减少非必要出行并做好安全防护。",
            300,
        )
        warning_event = _event(
            task, topic="weather", priority=priority, event_type="weather_alert",
            reason=f"official weather warning {severity}",
            payload={"title": title, "message": message, "reference_id": warning_id, "source": "QWeather"},
            dedupe_key=f"weather-warning:{_hash(warning_id, size=40)}:{severity}", requires_response=False,
            expires_hours=6, dedupe_policy="event_id",
            stable_time=(
                _parse_time(item.get("issuedTime"))
                or _parse_time(item.get("effectiveTime") or item.get("effective") or item.get("startTime"))
            ),
        )
        official_expiry = _parse_time(
            item.get("expireTime") or item.get("expire") or item.get("endTime")
        )
        if official_expiry is not None:
            warning_event["expires_at"] = int(official_expiry.timestamp() * 1000)
        warning_events.append((
            _SEVERITY_RANK[severity],
            warning_event,
            fingerprint,
        ))
    current_hazards = {item["type"] for item in hazards}
    hazard_events = []
    first_baseline = not isinstance(prior.get("baseline"), dict)
    cooldown_until = _parse_time(prior_status.get("cooldown_until"))
    cooldown_active = cooldown_until is not None and cooldown_until > now
    if not first_baseline and not cooldown_active:
        for hazard in hazards:
            if hazard["type"] in prior_hazards:
                continue
            window = hazard["window_start"][:13]
            fingerprint = "f:" + _hash(city["id"], hazard["type"], window, size=16)
            forecast_dedupe_key = f"weather-forecast:{city['id']}:{hazard['type']}"
            if fingerprint in fingerprints:
                continue
            hazard_events.append((
                _HAZARD_RANK[hazard["severity"]],
                _event(
                    task, topic="weather", priority="high", event_type="weather_alert",
                    reason=f"weather forecast deterioration {hazard['type']}",
                    payload={
                        "title": f"{city['name']}天气恶化提醒",
                        "message": _weather_text(city["name"], hazard["type"]),
                        "reference_id": f"{city['id']}:{hazard['type']}:{window}",
                        "source": "QWeather",
                    },
                    dedupe_key=forecast_dedupe_key,
                    requires_response=False, expires_hours=12,
                    dedupe_policy="rolling_window", dedupe_window_hours=12,
                    event_identity=f"{forecast_dedupe_key}:{window}",
                    stable_time=(
                        _parse_time(hazard["window_start"])
                        - timedelta(hours=int(config.get("forecast_hours", 6)))
                    ),
                ),
                fingerprint,
            ))
    planned = sorted(warning_events + hazard_events, key=lambda item: item[0], reverse=True)
    current_warning_hashes = {token.split(":", 1)[0] for token in active_warnings}
    for token in prior_warning_tokens:
        if (
            isinstance(token, str) and re.fullmatch(r"[0-9a-f]{12}:[mose]", token)
            and token.split(":", 1)[0] not in current_warning_hashes
        ):
            fingerprints = _append_fingerprint(
                {"fingerprints": fingerprints}, f"w:{token}", MAX_WEATHER_FINGERPRINTS
            )
    for _, _, fingerprint in planned:
        fingerprints = _append_fingerprint(
            {"fingerprints": fingerprints}, fingerprint, MAX_WEATHER_FINGERPRINTS
        )
    created = [event["event_id"] for _, event, _ in planned]
    persisted_hazards = (
        current_hazards & prior_hazards if cooldown_active else current_hazards
    )
    status = {
        "active_warning_ids": sorted(set(active_warnings)),
        "active_hazards": sorted(persisted_hazards),
    }
    if created:
        status["last_event_at"] = _iso(now)
        status["cooldown_until"] = _iso(now + timedelta(minutes=int(config.get("cooldown_minutes", 720))))
    elif isinstance(prior_status.get("last_event_at"), str):
        status["last_event_at"] = prior_status["last_event_at"]
        if isinstance(prior_status.get("cooldown_until"), str):
            status["cooldown_until"] = prior_status["cooldown_until"]
    state = {
        "schema_version": 1,
        "fingerprints": fingerprints[-MAX_WEATHER_FINGERPRINTS:],
        "detection_status": status,
        "baseline": {
            "captured_at": _iso(now),
            "location_id": city["id"],
            "hourly": hourly[:12],
            "hazards": hazards,
        },
    }
    while len(json.dumps(state, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > 4096:
        baseline = state["baseline"]
        if len(baseline["hourly"]) > 1:
            baseline["hourly"].pop()
        elif state["fingerprints"]:
            state["fingerprints"].pop(0)
        elif "warning_ids" in baseline:
            baseline.pop("warning_ids")
        else:
            break
    _validate_monitor_state("weather", state)
    for _, event, _ in planned:
        await _create_external_event_idempotently(event)
    return state, created


class NewsNowClient:
    def __init__(self, config):
        news = config.get("plugins", {}).get("get_news_from_newsnow", {})
        self.base_url = news.get("url", "https://newsnow.busiyi.world/api/s?id=")
        self._cache = {}

    async def fetch(self, source_name):
        source_id = CHANNEL_MAP.get(source_name)
        if not source_id:
            raise MonitorError("news_source_unsupported", "NewsNow来源不受支持")
        cached = self._cache.get(source_id)
        if cached and cached[0] > time.monotonic():
            return cached[1]
        try:
            async with httpx.AsyncClient(timeout=SOURCE_TIMEOUT, trust_env=False) as client:
                response = await client.get(self.base_url + source_id, headers={"User-Agent": "Mozilla/5.0"})
                response.raise_for_status()
                data = response.json()
        except httpx.TimeoutException as error:
            raise MonitorError("news_timeout", "NewsNow请求超时", retryable=True) from error
        except (httpx.HTTPError, ValueError) as error:
            raise MonitorError("news_request_failed", "NewsNow请求失败", retryable=True) from error
        items = data.get("items") if isinstance(data, dict) else None
        if not isinstance(items, list):
            raise MonitorError("news_response_invalid", "NewsNow响应结构无效")
        self._cache[source_id] = (time.monotonic() + 60, items)
        return items


def normalize_news_url(value):
    if not isinstance(value, str) or not value.strip():
        return ""
    try:
        parts = urlsplit(value.strip())
    except ValueError:
        return ""
    if (
        parts.scheme not in {"http", "https"}
        or not parts.hostname
        or parts.username is not None
        or parts.password is not None
    ):
        return ""
    return urlunsplit((parts.scheme.lower(), parts.netloc.lower(), parts.path.rstrip("/"), "", ""))


def normalize_news_title(value):
    text = unicodedata.normalize("NFKC", str(value or "")).lower()
    return re.sub(r"[^0-9a-z\u4e00-\u9fff]", "", text)


def _similar_news_title(left, right):
    if left == right:
        return True
    if min(len(left), len(right)) < 8:
        return False
    left_pairs = {left[index:index + 2] for index in range(len(left) - 1)}
    right_pairs = {right[index:index + 2] for index in range(len(right) - 1)}
    union = left_pairs | right_pairs
    return bool(union) and len(left_pairs & right_pairs) / len(union) >= 0.72


def prefilter_news(source_items):
    clusters = {}
    for source, items in source_items.items():
        if not isinstance(items, list):
            continue
        for position, item in enumerate(items[:20]):
            if not isinstance(item, dict):
                continue
            title = _trim(item.get("title"), 200)
            normalized_title = normalize_news_title(title)
            url = normalize_news_url(item.get("url") or item.get("mobileUrl"))
            if len(normalized_title) < 6 or not url or len(url) > 2048 or _EXCLUDED_NEWS.search(title):
                continue
            key = next(
                (existing for existing in clusters if _similar_news_title(existing, normalized_title)),
                normalized_title,
            )
            cluster = clusters.setdefault(key, {
                "title": title, "url": url, "sources": [], "position": position,
                "primary_source": source,
                "facts": _trim(item.get("description") or item.get("extra") or title, 500),
            })
            if source not in cluster["sources"]:
                cluster["sources"].append(source)
            cluster["position"] = min(cluster["position"], position)
    ranked = []
    for key, cluster in clusters.items():
        major_match = bool(_MAJOR_NEWS.search(cluster["title"]))
        if not major_match and len(cluster["sources"]) < 2:
            continue
        score = max((_SOURCE_WEIGHT.get(source, 1) for source in cluster["sources"]), default=1)
        score += max(0, 5 - cluster["position"]) + len(cluster["sources"]) * 3 + (8 if major_match else 0)
        cluster["cluster_id"] = _hash(key, size=40)
        cluster["score"] = score
        ranked.append(cluster)
    return sorted(ranked, key=lambda item: (-item["score"], item["cluster_id"]))[:20]


async def process_news(task, client):
    if task.get("news_sources_error"):
        raise MonitorError(task["news_sources_error"], "新闻来源配置错误")
    sources = task.get("news_sources")
    if not isinstance(sources, list) or not sources:
        raise MonitorError("news_sources_missing", "新闻来源为空")
    if any(source not in CHANNEL_MAP for source in sources):
        raise MonitorError("news_source_unsupported", "NewsNow来源不受支持")
    results = await asyncio.gather(*(client.fetch(source) for source in sources), return_exceptions=True)
    source_items = {}
    errors = []
    for source, result in zip(sources, results):
        if isinstance(result, BaseException):
            errors.append(result)
        else:
            source_items[source] = result
    if not source_items:
        error = errors[0] if errors else MonitorError("news_response_empty", "新闻来源没有数据")
        raise error
    candidates = prefilter_news(source_items)
    prior = task.get("state") if isinstance(task.get("state"), dict) else {}
    fingerprints = list(prior.get("fingerprints") or [])
    status = prior.get("detection_status") if isinstance(prior.get("detection_status"), dict) else {}
    if not candidates:
        state = {"schema_version": 1, "fingerprints": fingerprints[-MAX_NEWS_FINGERPRINTS:], "detection_status": dict(status)}
        return _validate_monitor_state("news", state), []
    now = _utc_now()
    config = task.get("config") if isinstance(task.get("config"), dict) else {}
    dedupe_hours = int(config.get("dedupe_hours", 24))
    cutoff = int((now - timedelta(hours=dedupe_hours)).timestamp())
    recent_clusters = set()
    kept_fingerprints = []
    for fingerprint in fingerprints:
        match = re.fullmatch(r"news:([0-9a-f]{40}):(\d+)", fingerprint)
        if match and int(match.group(2)) >= cutoff:
            kept_fingerprints.append(fingerprint)
            recent_clusters.add(match.group(1))
        elif not isinstance(fingerprint, str) or not fingerprint.startswith("news:"):
            kept_fingerprints.append(fingerprint)
    fingerprints = kept_fingerprints[-MAX_NEWS_FINGERPRINTS:]
    if "schema_version" not in prior:
        baseline_time = int(now.timestamp())
        for candidate in candidates:
            fingerprints = _append_fingerprint(
                {"fingerprints": fingerprints},
                f"news:{candidate['cluster_id']}:{baseline_time}",
            )
        state = {
            "schema_version": 1,
            "fingerprints": fingerprints[-MAX_NEWS_FINGERPRINTS:],
            "detection_status": {},
        }
        return _validate_monitor_state("news", state), []
    candidates = [item for item in candidates if item["cluster_id"] not in recent_clusters]
    if not candidates:
        state = {
            "schema_version": 1,
            "fingerprints": fingerprints[-MAX_NEWS_FINGERPRINTS:],
            "detection_status": dict(status),
        }
        return _validate_monitor_state("news", state), []
    cooldown_until = _parse_time(status.get("cooldown_until"))
    if cooldown_until is not None and cooldown_until > now:
        state = {
            "schema_version": 1,
            "fingerprints": fingerprints[-MAX_NEWS_FINGERPRINTS:],
            "detection_status": dict(status),
        }
        return _validate_monitor_state("news", state), []
    classifier_candidates = [
        {"title": item["title"], "source": "、".join(item["sources"])[:120], "facts": item["facts"]}
        for item in candidates
    ]
    try:
        classified = await evaluate_proactive_news_candidates(classifier_candidates)
    except ManageApiError as error:
        raise MonitorError("news_classifier_failed", "新闻分类模型调用失败") from error
    threshold = float(config.get("confidence", 0.85))
    categories = set(config.get("categories") or {
        "public_safety", "natural_disaster", "major_policy", "international_conflict",
        "major_economy", "major_technology",
    })
    accepted = []
    for verdict in classified["items"]:
        candidate = candidates[verdict["index"]]
        fingerprint = f"news:{candidate['cluster_id']}:{int(now.timestamp())}"
        if (
            verdict["is_major"] is True
            and verdict["severity"] in {"high", "critical"}
            and float(verdict["confidence"]) >= threshold
            and verdict["category"] in categories
        ):
            accepted.append((
                2 if verdict["severity"] == "critical" else 1,
                float(verdict["confidence"]), candidate["score"], candidate, verdict, fingerprint,
            ))
    if not accepted:
        state = {"schema_version": 1, "fingerprints": fingerprints[-MAX_NEWS_FINGERPRINTS:], "detection_status": dict(status)}
        return _validate_monitor_state("news", state), []
    _, _, _, candidate, verdict, fingerprint = max(accepted, key=lambda item: item[:3])
    facts = _trim("；".join(verdict["facts"]), 220)
    source = _trim(candidate["primary_source"], 64)
    message = _trim(
        verdict["spoken_summary"]
        + (f" 关键事实：{facts}" if facts and facts not in verdict["spoken_summary"] else ""),
        300,
    )
    payload = {
        "title": candidate["title"][:100],
        "message": message,
        "reference_id": candidate["cluster_id"],
        "reference_url": candidate["url"],
        "source": source,
    }
    event_bucket = int(now.timestamp()) // 3600
    news_dedupe_key = f"news:{candidate['cluster_id']}"
    event = _event(
        task, topic="news", priority=verdict["severity"], event_type="news_alert",
        reason=f"major news {verdict['category']}", payload=payload,
        dedupe_key=news_dedupe_key,
        requires_response=True, expires_hours=30,
        dedupe_policy="rolling_window", dedupe_window_hours=24,
        event_identity=f"{news_dedupe_key}:{event_bucket}",
        stable_time=datetime.fromtimestamp(event_bucket * 3600, tz=timezone.utc),
    )
    fingerprints = _append_fingerprint({"fingerprints": fingerprints}, fingerprint)
    new_status = {
        "last_event_at": _iso(now),
        "cooldown_until": _iso(now + timedelta(minutes=int(config.get("cooldown_minutes", 120)))),
        "last_cluster_id": candidate["cluster_id"],
    }
    state = {
        "schema_version": 1,
        "fingerprints": fingerprints[-MAX_NEWS_FINGERPRINTS:],
        "detection_status": new_status,
    }
    while len(json.dumps(state, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) > 4096:
        if not state["fingerprints"]:
            break
        state["fingerprints"].pop(0)
    _validate_monitor_state("news", state)
    await _create_external_event_idempotently(event)
    return state, [event["event_id"]]


class ExternalMonitorRunner:
    def __init__(self, config, *, poll_seconds=POLL_SECONDS, limit=MAX_TASKS):
        self.config = config
        self.poll_seconds = poll_seconds
        self.limit = limit
        self.lease_owner = _trim(
            f"py-{socket.gethostname()}-{os.getpid()}-{uuid.uuid4().hex[:8]}", 64
        )
        self.weather = QWeatherClient(config)
        self.news = NewsNowClient(config)
        self._task = None
        self._run_lock = asyncio.Lock()

    async def start(self):
        if self._task is not None and not self._task.done():
            raise RuntimeError("外界监测runner已经启动")
        self._task = asyncio.create_task(self._loop(), name="external-monitor-runner")

    async def stop(self):
        task = self._task
        self._task = None
        if task is None:
            return
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)

    async def _loop(self):
        while True:
            try:
                await self.run_once()
            except asyncio.CancelledError:
                raise
            except Exception as error:
                logger.bind(tag=TAG).error("外界监测轮询失败: {}", type(error).__name__)
            await asyncio.sleep(self.poll_seconds)

    async def run_once(self):
        if self._run_lock.locked():
            return 0
        async with self._run_lock:
            tasks = await claim_proactive_monitor_tasks(self.lease_owner, self.limit)
            if not tasks:
                return 0
            await asyncio.gather(*(self._process_task(task) for task in tasks))
            return len(tasks)

    async def _process_task(self, task):
        state = task.get("state") if isinstance(task.get("state"), dict) else {}
        try:
            monitor_type = task.get("monitor_type")
            if monitor_type not in {"weather", "news"}:
                raise MonitorError("monitor_type_invalid", "未知监测类型")
            for attempt in range(3):
                try:
                    if monitor_type == "weather":
                        new_state, _ = await process_weather(task, self.weather)
                    else:
                        new_state, _ = await process_news(task, self.news)
                    break
                except MonitorError as error:
                    if not error.retryable or attempt == 2:
                        raise
                    await asyncio.sleep(0.25 * (2 ** attempt))
            await complete_proactive_monitor_task(task, success=True, state=new_state)
        except asyncio.CancelledError:
            raise
        except MonitorError as error:
            await self._complete_failure(task, state, error.code)
        except Exception as error:
            logger.bind(tag=TAG).error(
                "外界监测任务失败: type={}, device_hash={}",
                type(error).__name__, _hash(task.get("device_id"), size=12),
            )
            await self._complete_failure(task, state, "monitor_internal_error")

    async def _complete_failure(self, task, state, error_code):
        try:
            await complete_proactive_monitor_task(
                task, success=False, state=state, error_code=_trim(error_code, 64)
            )
        except Exception as complete_error:
            logger.bind(tag=TAG).error(
                "外界监测失败状态回写失败: {}", type(complete_error).__name__
            )
