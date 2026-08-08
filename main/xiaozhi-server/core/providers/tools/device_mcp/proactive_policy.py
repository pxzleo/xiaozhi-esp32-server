"""积极主动场景的轻量限流策略。"""

import threading
import time
from dataclasses import dataclass, field
from datetime import datetime


DEFAULT_DAILY_LIMIT = 3
MAX_TRACKED_DEVICES = 1024


@dataclass
class _DevicePolicyState:
    day: str
    used: int = 0
    topic_times: dict[str, float] = field(default_factory=dict)


_states: dict[str, _DevicePolicyState] = {}
_lock = threading.Lock()


def _device_key(conn) -> str:
    headers = getattr(conn, "headers", None) or {}
    header_id = headers.get("device-id") if isinstance(headers, dict) else None
    if isinstance(header_id, str) and header_id.strip():
        return header_id.strip()
    device_id = getattr(conn, "device_id", "")
    if isinstance(device_id, str) and device_id.strip():
        return device_id.strip()
    return str(id(conn))


def claim_proactive_opportunity(
    conn,
    topic: str,
    *,
    cooldown_seconds: int,
    daily_limit: int = DEFAULT_DAILY_LIMIT,
    now: float | None = None,
) -> bool:
    """为设备领取一次非紧急主动发言机会。"""
    if not topic or cooldown_seconds < 0 or daily_limit < 1:
        raise ValueError("主动机会参数无效")
    current = time.time() if now is None else now
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
        if state.used >= daily_limit:
            return False
        if previous is not None and current - previous < cooldown_seconds:
            return False
        state.used += 1
        state.topic_times[topic] = current
        return True


def reset_proactive_policy_for_test() -> None:
    with _lock:
        _states.clear()
