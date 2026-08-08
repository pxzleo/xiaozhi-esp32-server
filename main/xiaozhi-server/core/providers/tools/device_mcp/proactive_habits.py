"""仅基于真实成功行为的受控习惯证据与建议。"""

import asyncio
import hashlib
from datetime import datetime, timezone

from config.logger import setup_logging
from config.manage_api_client import (
    create_proactive_event,
    get_proactive_habit_candidates,
    observe_proactive_habit,
    update_proactive_event_status,
)
from core.providers.tools.device_mcp.proactive_policy import claim_proactive_opportunity

TAG = __name__
logger = setup_logging()

_SUGGESTION_TEXT = {
    "schedule": "我发现你经常在固定时间安排事情，要不要设成重复日程？",
    "daily_briefing": "我发现你经常收听每日简报，要不要保留这个安排？",
    "music": "我发现你经常播放这类音乐，要不要下次直接为你播放？",
}


def schedule_candidate_suggestion(conn):
    async def runner():
        try:
            candidates = await get_proactive_habit_candidates(conn.device_id)
            for candidate in candidates:
                if await suggest_habit_candidate(conn, candidate):
                    break
        except Exception as error:
            logger.bind(tag=TAG).error(f"习惯候选加载失败: {type(error).__name__}")

    task = asyncio.create_task(runner())
    tasks = getattr(conn, "_proactive_habit_tasks", None)
    if tasks is None:
        tasks = set()
        conn._proactive_habit_tasks = tasks
    tasks.add(task)
    task.add_done_callback(tasks.discard)


async def suggest_habit_candidate(conn, candidate):
    if not isinstance(candidate, dict) or candidate.get("evidence_count", 0) < 3:
        return False
    habit_key = candidate.get("habit_key")
    if not isinstance(habit_key, str):
        return False
    habit_group = habit_key.split(":", 1)[0]
    if habit_group not in _SUGGESTION_TEXT:
        return False
    mac_address = getattr(conn, "device_id", None)
    habit_type = candidate.get("habit_type")
    if not isinstance(mac_address, str) or not isinstance(habit_type, str):
        return False
    return await _suggest_habit(
        conn,
        mac_address,
        habit_group,
        habit_type,
        habit_key,
        candidate.get("first_seen_at"),
    )


def schedule_habit_observation(
    conn, habit_group, habit_type, habit_key, payload, notification_state=None,
    allow_suggestion=True,
):
    """调度证据写入；后台异常只记录类型，不记录 payload。"""
    async def runner():
        try:
            await observe_habit_and_maybe_suggest(
                conn,
                habit_group,
                habit_type,
                habit_key,
                payload,
                notification_state,
                allow_suggestion,
            )
        except Exception as error:
            logger.bind(tag=TAG).error(f"习惯观察失败: {type(error).__name__}")

    task = asyncio.create_task(runner())
    tasks = getattr(conn, "_proactive_habit_tasks", None)
    if tasks is None:
        tasks = set()
        conn._proactive_habit_tasks = tasks
    tasks.add(task)
    task.add_done_callback(tasks.discard)


async def observe_habit_and_maybe_suggest(
    conn, habit_group, habit_type, habit_key, payload, notification_state=None,
    allow_suggestion=True,
):
    if habit_group not in _SUGGESTION_TEXT:
        raise ValueError("习惯分组无效")
    mac_address = getattr(conn, "device_id", None)
    if not isinstance(mac_address, str) or not mac_address:
        raise ValueError("设备MAC缺失")
    now = datetime.now(timezone.utc)
    observed = await observe_proactive_habit(
        {
            "mac_address": mac_address,
            "habit_type": habit_type,
            "habit_key": habit_key,
            "evidence_delta": 1,
            "seen_at": now.isoformat().replace("+00:00", "Z"),
            "payload": payload,
        }
    )
    if not isinstance(observed, dict) or observed.get("evidence_count", 0) < 3:
        return False
    if not allow_suggestion:
        return False
    return await _suggest_habit(
        conn,
        mac_address,
        habit_group,
        habit_type,
        habit_key,
        observed.get("first_seen_at") or now.isoformat().replace("+00:00", "Z"),
        notification_state,
    )


async def _suggest_habit(
    conn, mac_address, habit_group, habit_type, habit_key, created_at,
    notification_state=None,
):
    if (
        not isinstance(created_at, (str, int, float))
        or isinstance(created_at, bool)
        or created_at == ""
    ):
        return False
    event_id = "habit-" + hashlib.sha256(
        f"{mac_address}:{habit_type}:{habit_key}".encode("utf-8")
    ).hexdigest()[:40]
    claims = getattr(conn, "_habit_suggestion_events", None)
    if claims is None:
        claims = set()
        conn._habit_suggestion_events = claims
    if event_id in claims:
        return False
    event = {
        "mac_address": mac_address,
        "event_id": event_id,
        "topic": "habit",
        "priority": "low",
        "reason": "habit evidence threshold reached",
        "event_type": "habit_suggestion",
        "payload": {"title": "习惯建议", "reference_id": habit_key, "source": "server"},
        "created_at": created_at,
        "expires_at": None,
        "dedupe_key": event_id,
        "requires_response": True,
    }
    stored = await create_proactive_event(event)
    if isinstance(stored, dict) and stored.get("delivery_status") == "delivered":
        claims.add(event_id)
        return False
    if not claim_proactive_opportunity(
        conn, event_id, cooldown_seconds=365 * 24 * 3600, policy_topic="habit"
    ):
        return False
    from core.providers.tools.device_mcp.mcp_handler import _speak_proactive_notification

    claims.add(event_id)
    sentence_id = await _speak_proactive_notification(
        conn, _SUGGESTION_TEXT[habit_group], "习惯建议", notification_state
    )
    await update_proactive_event_status(
        event_id,
        mac_address,
        "delivered" if sentence_id else "failed",
        "none" if sentence_id else "failed",
    )
    return sentence_id is not None
