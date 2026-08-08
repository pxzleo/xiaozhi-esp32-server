"""服务端生成主动建议的统一异步审计。"""

import asyncio
import uuid
from datetime import datetime, timezone

from config.logger import setup_logging
from config.manage_api_client import create_proactive_event, update_proactive_event_status

TAG = __name__
logger = setup_logging()


def schedule_server_suggestion_audit(
    conn, topic, reason, *, reference_id, delivered, requires_response=True
):
    mac_address = getattr(conn, "device_id", None)
    if not isinstance(mac_address, str) or not mac_address:
        return
    event_id = uuid.uuid4().hex
    now = datetime.now(timezone.utc)
    event = {
        "mac_address": mac_address,
        "event_id": event_id,
        "topic": topic,
        "priority": "low",
        "reason": reason,
        "event_type": "habit_suggestion" if topic == "habit" else "system",
        "payload": {
            "title": "主动建议",
            "reference_id": str(reference_id),
            "source": "server",
        },
        "created_at": now.isoformat().replace("+00:00", "Z"),
        "expires_at": datetime.fromtimestamp(now.timestamp() + 86400, timezone.utc)
        .isoformat()
        .replace("+00:00", "Z"),
        "dedupe_key": f"{topic}:{reference_id}:{event_id}",
        "requires_response": requires_response,
    }

    async def audit():
        delivery_result = None
        try:
            await create_proactive_event(event)
            delivery_result = delivered() if callable(delivered) else delivered
            delivery_succeeded = (
                await delivery_result
                if hasattr(delivery_result, "__await__")
                else delivery_result is True
            )
            await update_proactive_event_status(
                event_id,
                mac_address,
                "delivered" if delivery_succeeded else "failed",
                "none" if delivery_succeeded else "failed",
            )
            return True
        except Exception as error:
            if isinstance(delivery_result, asyncio.Future):
                if not delivery_result.done():
                    delivery_result.set_result(False)
            else:
                close = getattr(delivery_result, "close", None)
                if callable(close):
                    close()
            logger.bind(tag=TAG).error(f"主动建议审计失败: {type(error).__name__}")
            return False

    task = asyncio.create_task(audit())
    tasks = getattr(conn, "_proactive_audit_tasks", None)
    if tasks is None:
        tasks = set()
        conn._proactive_audit_tasks = tasks
    tasks.add(task)
    task.add_done_callback(tasks.discard)
