import asyncio
import unittest
from datetime import datetime
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from config import manage_api_client
from core.providers.tools.device_mcp import mcp_handler
from core.providers.tools.device_mcp.daily_briefing import weather_action_suggestion
from core.providers.tools.device_mcp.proactive_habits import (
    observe_habit_and_maybe_suggest,
)
from core.providers.tools.device_mcp.proactive_policy import (
    reset_proactive_policy_for_test,
    safe_local_preferences,
)
from plugins_func.functions.play_netease_music import _late_night_music_suggestion


def _event_fields(**overrides):
    data = {
        "event_id": "event-1",
        "topic": "follow_up",
        "priority": "normal",
        "reason": "schedule follow up",
        "created_at": 1_786_170_600,
        "expires_at": 1_786_174_200,
        "dedupe_key": "dedupe-1",
        "requires_response": True,
    }
    data.update(overrides)
    return data


class ProactiveNotificationV2Test(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        reset_proactive_policy_for_test()
        self.conn = SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences=safe_local_preferences(),
        )

    async def test_follow_up_uses_fixed_text_and_deduplicates(self):
        params = {
            **_event_fields(),
            "version": 1,
            "follow_up": True,
            "source_id": 7,
            "label": " 喝水 ",
            "speak": True,
        }
        with patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock(return_value="sid")
        ) as speak, patch.object(mcp_handler, "_schedule_delivery_audit") as audit:
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
        speak.assert_awaited_once()
        self.assertEqual("刚才提醒的喝水完成了吗？", speak.await_args.args[1])
        audit.assert_called_once()

    async def test_critical_health_bypasses_conservative_policy(self):
        self.conn.proactive_preferences = {
            **safe_local_preferences(),
            "mode": "conservative",
            "daily_limit": 1,
        }
        params = {
            **_event_fields(
                topic="health_critical",
                priority="critical",
                reason="device health",
                requires_response=False,
            ),
            "version": 1,
            "kind": "audio_decode_failed",
            "severity": "critical",
            "occurred_at": 1_786_170_600,
            "recovered": False,
            "details": {"error_code": "17"},
        }
        with patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock(return_value="sid")
        ) as speak, patch.object(mcp_handler, "_schedule_delivery_audit"):
            await mcp_handler._handle_device_health_notification(self.conn, params)
        speak.assert_awaited_once()

    async def test_health_rejects_unknown_detail_without_speaking(self):
        params = {
            **_event_fields(
                topic="health", priority="high", reason="device health", requires_response=False
            ),
            "version": 1,
            "kind": "network_flapping",
            "severity": "warning",
            "occurred_at": 1_786_170_600,
            "recovered": False,
            "details": {"label": "secret"},
        }
        with patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock()
        ) as speak:
            await mcp_handler._handle_device_health_notification(self.conn, params)
        speak.assert_not_awaited()

    async def test_successful_proactive_tool_syncs_preference_with_one_retry(self):
        preference = {
            "mode": "active",
            "daily_limit": 2,
            "quiet_start": None,
            "quiet_end": None,
            "allowed_topics": ["music"],
            "blocked_topics": [],
        }
        with patch.object(
            mcp_handler,
            "update_proactive_preference",
            AsyncMock(side_effect=[RuntimeError("offline"), preference]),
        ) as update:
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.proactive.set_mode",
                {"action": "RESPONSE", "data": preference},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        self.assertEqual(preference, self.conn.proactive_preferences)
        self.assertEqual(2, update.await_count)

    async def test_successful_schedule_completion_updates_follow_up_outcome(self):
        self.conn._current_followup_event_id = "event-1"
        with patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as update:
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.complete",
                {"action": "RESPONSE", "data": {}},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        update.assert_awaited_once_with(
            "event-1", "AA:BB", "delivered", "completed"
        )


class ManageApiProactiveClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_strict_wrapper_uses_expected_endpoint_without_logging_payload(self):
        client = SimpleNamespace(_execute_async_request=AsyncMock(return_value={"mode": "active"}))
        with patch.object(manage_api_client.ManageApiClient, "_instance", client):
            await manage_api_client.get_proactive_preference("AA:BB")
            await manage_api_client.create_proactive_event({"event_id": "x"})
            await manage_api_client.update_proactive_event_status(
                "x", "AA:BB", "delivered"
            )
            await manage_api_client.observe_proactive_habit({"habit_key": "x"})
            client._execute_async_request.return_value = []
            await manage_api_client.get_proactive_habit_candidates("AA:BB")
        calls = client._execute_async_request.await_args_list
        self.assertEqual("GET", calls[0].args[0])
        self.assertIn("preferences/AA%3ABB", calls[0].args[1])
        self.assertEqual("POST", calls[1].args[0])
        self.assertEqual("PUT", calls[2].args[0])
        self.assertEqual("POST", calls[3].args[0])
        self.assertEqual("GET", calls[4].args[0])


class ContextSuggestionTest(unittest.TestCase):
    def setUp(self):
        reset_proactive_policy_for_test()
        self.conn = SimpleNamespace(
            headers={"device-id": "device-a"},
            proactive_preferences=safe_local_preferences(),
        )

    def test_late_night_music_is_once_per_night(self):
        now = datetime(2026, 8, 8, 23, 0)
        self.assertIn("轻音乐", _late_night_music_suggestion(self.conn, now))
        self.assertEqual("", _late_night_music_suggestion(self.conn, now))
        self.assertEqual(
            "", _late_night_music_suggestion(self.conn, datetime(2026, 8, 9, 0, 30))
        )
        self.assertEqual("", _late_night_music_suggestion(self.conn, datetime(2026, 8, 9, 12, 0)))

    def test_weather_requires_explicit_keyword(self):
        self.assertIn("带伞", weather_action_suggestion(self.conn, "广州今天有雨。"))
        self.assertEqual("", weather_action_suggestion(self.conn, "广州今天多云。"))


class HabitSuggestionTest(unittest.IsolatedAsyncioTestCase):
    async def test_threshold_three_suggests_once_and_audits(self):
        conn = SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences=safe_local_preferences(),
        )
        with patch(
            "core.providers.tools.device_mcp.proactive_habits.observe_proactive_habit",
            AsyncMock(return_value={"evidence_count": 3}),
        ), patch(
            "core.providers.tools.device_mcp.proactive_habits.create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch(
            "core.providers.tools.device_mcp.proactive_habits.update_proactive_event_status",
            AsyncMock(),
        ) as status, patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock(return_value="sid")
        ) as speak:
            first = await observe_habit_and_maybe_suggest(
                conn,
                "music",
                "content_preference",
                "music:category:abc",
                {"description": "播放偏好的音乐内容", "topic": "music"},
            )
            second = await observe_habit_and_maybe_suggest(
                conn,
                "music",
                "content_preference",
                "music:category:abc",
                {"description": "播放偏好的音乐内容", "topic": "music"},
            )
        self.assertTrue(first)
        self.assertFalse(second)
        speak.assert_awaited_once()
        status.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
