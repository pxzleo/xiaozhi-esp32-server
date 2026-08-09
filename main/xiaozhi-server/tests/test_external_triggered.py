import asyncio
import unittest
import time
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from core.providers.tools.device_mcp import mcp_handler
from core.providers.tools.device_mcp.proactive_policy import reset_proactive_policy_for_test


def event(kind="news"):
    is_news = kind == "news"
    return {
        "event_id": "ext-1", "mac_address": "AA:BB",
        "event_type": "news_alert" if is_news else "weather_alert",
        "topic": "news" if is_news else "weather",
        "priority": "high" if is_news else "critical",
        "delivery_status": "pending", "requires_response": is_news,
        "expires_at": int((time.time() + 3600) * 1000),
        "payload": {
            "title": "重大新闻" if is_news else "暴雨红色预警",
            "message": "这是权威数据库中的播报内容",
            "reference_id": "cluster-1" if is_news else "warning-1",
            "source": "澎湃新闻、财联社" if is_news else "QWeather",
            "action": "已启动应急响应" if is_news else "请减少外出",
            **({"reference_url": "https://news.example/a"} if is_news else {}),
        },
    }


def connection():
    return SimpleNamespace(
        device_id="AA:BB", sentence_id="old", abort_generation=0, client_abort=False,
        proactive_preferences={
            "mode": "active", "daily_limit": 5, "quiet_start": None, "quiet_end": None,
            "allowed_topics": [], "blocked_topics": [],
        },
        dialogue=Mock(), _proactive_audit_tasks=set(), close_after_chat=True,
    )


class ExternalTriggeredTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        reset_proactive_policy_for_test()

    async def test_rejects_device_text_and_does_not_read_event(self):
        conn = connection()
        params = {"version": 1, "event_id": "ext-1", "speak": True, "text": "伪造播报"}
        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock()) as get_event:
            await mcp_handler._handle_external_triggered_notification(conn, params)
        get_event.assert_not_awaited()

    async def test_news_reads_authority_claims_delivers_and_keeps_listening_context(self):
        conn = connection()

        async def speak(target, text, name, state, completion_event=None):
            self.assertNotIn("伪造", text)
            self.assertTrue(text.endswith("要了解详情吗？"))
            target.sentence_id = "sid"
            completion_event.set_result(True)
            return "sid"

        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event())), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(mcp_handler, "_speak_proactive_notification", speak), patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as status:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
            await asyncio.gather(*conn._proactive_audit_tasks)
        self.assertFalse(conn.close_after_chat)
        self.assertTrue(conn._external_news_waiting_response)
        self.assertEqual("https://news.example/a", conn.last_newsnow_link["url"])
        self.assertEqual("thepaper", conn.last_newsnow_link["source_id"])
        self.assertEqual("delivered", status.await_args.args[2])

    async def test_weather_does_not_enter_listen_and_tts_failure_is_terminal(self):
        conn = connection()
        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event("weather"))), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(mcp_handler, "_speak_proactive_notification", AsyncMock(return_value=None)), patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as status:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
        self.assertFalse(conn._external_news_waiting_response)
        self.assertEqual("failed", status.await_args.args[2])

    async def test_user_interrupt_completion_writes_failed(self):
        conn = connection()

        async def speak(target, _text, _name, _state, completion_event=None):
            target.sentence_id = "sid"
            completion_event.set_result(False)
            return "sid"

        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event())), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(mcp_handler, "_speak_proactive_notification", speak), patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as status:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
            await asyncio.gather(*conn._proactive_audit_tasks)
        self.assertEqual("failed", status.await_args.args[2])

    async def test_delivery_status_retries_transient_manager_failure(self):
        conn = connection()

        async def speak(target, _text, _name, _state, completion_event=None):
            target.sentence_id = "sid"
            completion_event.set_result(True)
            return "sid"

        status = AsyncMock(side_effect=[RuntimeError("temporary"), {}])
        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event())), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(mcp_handler, "_speak_proactive_notification", speak), patch.object(
            mcp_handler, "update_proactive_event_status", status
        ), patch.object(mcp_handler.asyncio, "sleep", AsyncMock()) as sleep:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
            await asyncio.gather(*conn._proactive_audit_tasks)
        self.assertEqual(2, status.await_count)
        sleep.assert_awaited_once_with(0.5)

    async def test_disconnect_before_completion_writes_failed(self):
        conn = connection()
        conn.connection_closed_event = asyncio.Event()

        async def speak(target, _text, _name, _state, completion_event=None):
            target.sentence_id = "sid"
            return "sid"

        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event())), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(mcp_handler, "_speak_proactive_notification", speak), patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as status:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
            conn.connection_closed_event.set()
            await asyncio.gather(*conn._proactive_audit_tasks)
        self.assertEqual("failed", status.await_args.args[2])

    async def test_news_policy_suppression_dismisses_event(self):
        conn = connection()
        conn.proactive_preferences["mode"] = "today_silent"
        conn.proactive_preferences["daily_limit"] = 0
        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event())), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock()
        ) as claim, patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as status:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
        claim.assert_not_awaited()
        status.assert_awaited_once_with(
            "ext-1", "AA:BB", "dismissed", "dismissed", claim_token=None
        )

    async def test_critical_weather_bypasses_quiet_policy(self):
        conn = connection()
        conn.proactive_preferences.update({"mode": "today_silent", "daily_limit": 0})
        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event("weather"))), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=False)
        ) as claim:
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
        claim.assert_awaited_once()

    async def test_failed_database_claim_releases_local_quota_and_cooldown(self):
        conn = connection()
        claim = AsyncMock(side_effect=[False, True])
        with patch.object(mcp_handler, "get_proactive_monitor_event", AsyncMock(return_value=event())), patch.object(
            mcp_handler, "claim_proactive_event", claim
        ), patch.object(mcp_handler, "_speak_proactive_notification", AsyncMock(return_value=None)), patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ):
            params = {"version": 1, "event_id": "ext-1", "speak": True}
            await mcp_handler._handle_external_triggered_notification(conn, params)
            await mcp_handler._handle_external_triggered_notification(conn, params)
        self.assertEqual(2, claim.await_count)

    async def test_distinct_weather_events_do_not_share_local_cooldown(self):
        conn = connection()
        first = event("weather")
        second = event("weather")
        second["event_id"] = "ext-2"
        second["payload"]["reference_id"] = "warning-2"
        with patch.object(
            mcp_handler, "get_proactive_monitor_event", AsyncMock(side_effect=[first, second])
        ), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ) as claim, patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock(return_value=None)
        ), patch.object(mcp_handler, "update_proactive_event_status", AsyncMock()):
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-1", "speak": True}
            )
            await mcp_handler._handle_external_triggered_notification(
                conn, {"version": 1, "event_id": "ext-2", "speak": True}
            )
        self.assertEqual(2, claim.await_count)


if __name__ == "__main__":
    unittest.main()
