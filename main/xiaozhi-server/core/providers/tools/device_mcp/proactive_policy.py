"""积极主动场景的连接偏好与进程内限流策略。"""

import threading
import time
from dataclasses import dataclass, field
from datetime import datetime


DEFAULT_DAILY_LIMIT = 5
MAX_TRACKED_DEVICES = 1024
_KNOWN_TOPICS = {"reminder", "calendar", "weather", "music", "health", "habit", "system"}


@dataclass
class _DevicePolicyState:
    day: str
    used: int = 0
    topic_times: dict[str, float] = field(default_factory=dict)


_states: dict[str, _DevicePolicyState] = {}
_lock = threading.Lock()


def safe_local_preferences() -> dict:
    """manager-api 不可用时的保守本地默认，不猜测安静时段。"""
    return {
        "mode": "active",
        "daily_limit": DEFAULT_DAILY_LIMIT,
        "quiet_start": None,
        "quiet_end": None,
        "allowed_topics": [],
        "blocked_topics": [],
    }


def set_connection_preferences(conn, preferences) -> dict:
    """严格归一化 manager-api 偏好后绑定到当前连接。"""
    if not isinstance(preferences, dict):
        raise ValueError("积极主动偏好必须是对象")
    mode = preferences.get("mode")
    if mode not in ("conservative", "active", "aggressive", "today_silent"):
        raise ValueError("积极主动偏好 mode 无效")
    daily_limit = preferences.get("daily_limit")
    if not isinstance(daily_limit, int) or isinstance(daily_limit, bool) or not 0 <= daily_limit <= 5:
        raise ValueError("积极主动偏好 daily_limit 无效")
    valid_limits = {
        "conservative": {1},
        "active": {1, 2, 3, 4, 5},
        # 兼容 manager-api 规范化前读出的旧部署数据；绑定后统一为不限次数的 0。
        "aggressive": {0, 1, 2, 3, 4, 5},
        "today_silent": {0},
    }
    if daily_limit not in valid_limits[mode]:
        raise ValueError("积极主动偏好 mode 与 daily_limit 不匹配")
    if mode == "aggressive":
        daily_limit = 0
    quiet_start = preferences.get("quiet_start")
    quiet_end = preferences.get("quiet_end")
    if (quiet_start is None) != (quiet_end is None):
        raise ValueError("安静时段必须同时给出起止时间")
    if quiet_start is not None and quiet_start == quiet_end:
        raise ValueError("安静时段起止时间不能相同")
    for value in (quiet_start, quiet_end):
        if value is not None:
            if not isinstance(value, str):
                raise ValueError("安静时段格式无效")
            for time_format in ("%H:%M", "%H:%M:%S"):
                try:
                    datetime.strptime(value, time_format)
                    break
                except ValueError:
                    continue
            else:
                raise ValueError("安静时段格式无效")
    allowed = preferences.get("allowed_topics") or []
    blocked = preferences.get("blocked_topics") or []
    if not isinstance(allowed, (list, set, tuple)) or not set(allowed) <= _KNOWN_TOPICS:
        raise ValueError("allowed_topics 无效")
    if not isinstance(blocked, (list, set, tuple)) or not set(blocked) <= _KNOWN_TOPICS:
        raise ValueError("blocked_topics 无效")
    if set(allowed) & set(blocked):
        raise ValueError("允许与屏蔽主题不能重叠")
    normalized = {
        "mode": mode,
        "daily_limit": daily_limit,
        "quiet_start": quiet_start,
        "quiet_end": quiet_end,
        "allowed_topics": sorted(set(allowed)),
        "blocked_topics": sorted(set(blocked)),
    }
    conn.proactive_preferences = normalized
    return normalized


def _device_key(conn) -> str:
    headers = getattr(conn, "headers", None) or {}
    header_id = headers.get("device-id") if isinstance(headers, dict) else None
    if isinstance(header_id, str) and header_id.strip():
        return header_id.strip()
    device_id = getattr(conn, "device_id", "")
    if isinstance(device_id, str) and device_id.strip():
        return device_id.strip()
    return str(id(conn))


def _in_quiet_window(preferences: dict, current: float) -> bool:
    start = preferences.get("quiet_start")
    end = preferences.get("quiet_end")
    if start is None or end is None:
        return False
    now_value = datetime.fromtimestamp(current).strftime("%H:%M:%S")
    if start < end:
        return start <= now_value < end
    return now_value >= start or now_value < end


def policy_allows(conn, topic: str, *, critical: bool = False, now: float | None = None) -> bool:
    """检查偏好层；关键通知和恢复通知由调用方以 critical 显式放行。"""
    if critical:
        return True
    preferences = getattr(conn, "proactive_preferences", None)
    if not isinstance(preferences, dict):
        preferences = safe_local_preferences()
    if preferences.get("mode") in ("conservative", "today_silent"):
        return False
    policy_topic = topic if topic in _KNOWN_TOPICS else topic.split("_", 1)[0]
    allowed = set(preferences.get("allowed_topics") or [])
    blocked = set(preferences.get("blocked_topics") or [])
    if policy_topic in blocked or (allowed and policy_topic not in allowed):
        return False
    return not _in_quiet_window(preferences, time.time() if now is None else now)


def claim_proactive_opportunity(
    conn,
    topic: str,
    *,
    cooldown_seconds: int,
    daily_limit: int | None = None,
    now: float | None = None,
    policy_topic: str | None = None,
    critical: bool = False,
) -> bool:
    """原子领取一次主动发言机会，兼容旧调用签名。"""
    if not topic or cooldown_seconds < 0:
        raise ValueError("主动机会参数无效")
    current = time.time() if now is None else now
    if not policy_allows(conn, policy_topic or topic, critical=critical, now=current):
        return False
    preferences = getattr(conn, "proactive_preferences", None)
    if not isinstance(preferences, dict):
        preferences = None
    effective_limit = daily_limit
    if effective_limit is None:
        effective_limit = (
            preferences.get("daily_limit", DEFAULT_DAILY_LIMIT)
            if isinstance(preferences, dict)
            else DEFAULT_DAILY_LIMIT
        )
    unlimited = isinstance(preferences, dict) and preferences.get("mode") == "aggressive"
    if (not isinstance(effective_limit, int) or isinstance(effective_limit, bool) or
            (effective_limit < 1 and not (unlimited and effective_limit == 0))):
        raise ValueError("主动机会参数无效")
    # 关键事件不消耗普通建议预算。
    if critical:
        return True
    day = datetime.fromtimestamp(current).strftime("%Y-%m-%d")
    key = _device_key(conn)
    with _lock:
        state = _states.get(key)
        if state is None:
            if len(_states) >= MAX_TRACKED_DEVICES:
                _states.pop(next(iter(_states)))
            state = _DevicePolicyState(day=day)
            _states[key] = state
        elif state.day < day:
            state = _DevicePolicyState(day=day)
            _states[key] = state
        previous = state.topic_times.get(topic)
        if previous is not None and current - previous < cooldown_seconds:
            return False
        if not unlimited and state.used >= effective_limit:
            return False
        state.used += 1
        state.topic_times[topic] = current
        return True


def reset_proactive_policy_for_test() -> None:
    with _lock:
        _states.clear()
