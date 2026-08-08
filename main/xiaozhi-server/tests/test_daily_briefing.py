import unittest
import threading
from types import SimpleNamespace
from unittest.mock import ANY, AsyncMock, Mock, patch

from core.providers.tools.device_mcp.daily_briefing import build_daily_briefing
from core.providers.tools.device_mcp.mcp_handler import (
    MCPClient,
    _speak_proactive_notification,
    handle_mcp_message,
)


class DailyBriefingTest(unittest.IsolatedAsyncioTestCase):
    async def test_superseded_reminder_does_not_claim_completion_budget(self):
        conn = Mock()
        conn.sentence_id = "new-turn"
        conn.abort_generation = 3
        conn.stop_event.is_set.return_value = False
        conn.connection_closed_event.is_set.return_value = False
        transform = Mock(return_value="不应播报")

        result = await _speak_proactive_notification(
            conn,
            "提醒你：喝水",
            "日程提醒",
            notification_state=("old-turn", 2),
            text_transform=transform,
        )

        self.assertIsNone(result)
        transform.assert_not_called()

    async def test_proactive_completion_event_is_attached_after_tts_stop(self):
        conn = Mock()
        conn.sentence_id = "old-turn"
        conn.abort_generation = 2
        conn.client_abort = False
        conn.tts_control_generation = 0
        conn.session_id = "briefing-session"
        conn.websocket.send = AsyncMock()
        completion_event = threading.Event()

        with patch(
            "core.providers.tools.device_mcp.mcp_handler.cancelActiveLLMResponse",
            AsyncMock(return_value=True),
        ):
            sentence_id = await _speak_proactive_notification(
                conn,
                "简报内容",
                "每日简报",
                completion_event=completion_event,
            )

        self.assertEqual(sentence_id, conn.sentence_id)
        last = conn.tts.tts_text_queue.put.call_args_list[-1].args[0]
        self.assertEqual(last.sentence_type.name, "LAST")
        self.assertIs(last.completion_event, completion_event)

    async def test_partial_provider_failure_still_returns_available_section(self):
        conn = SimpleNamespace()
        with patch(
            "core.providers.tools.device_mcp.daily_briefing._weather_summary",
            AsyncMock(side_effect=RuntimeError("weather down")),
        ), patch(
            "core.providers.tools.device_mcp.daily_briefing._news_summary",
            AsyncMock(return_value="今日新闻：一条。"),
        ):
            result = await build_daily_briefing(conn, ["weather", "news"], "广州")
        self.assertEqual("这是你的每日简报。今日新闻：一条。", result)

    async def test_all_provider_failures_use_fixed_fallback(self):
        conn = SimpleNamespace()
        failure = AsyncMock(side_effect=RuntimeError("down"))
        with patch(
            "core.providers.tools.device_mcp.daily_briefing._weather_summary", failure
        ), patch(
            "core.providers.tools.device_mcp.daily_briefing._news_summary", failure
        ):
            result = await build_daily_briefing(conn, ["weather", "news"], "广州")
        self.assertEqual("天气和新闻服务暂时不可用，请稍后再试。", result)

    async def test_valid_notification_is_built_once(self):
        conn = SimpleNamespace(sentence_id="old", abort_generation=0)
        payload = {
            "method": "notifications/assistant/triggered",
            "params": {
                "version": 1,
                "id": 17,
                "event_id": "17-20260810T080000",
                "workflow": "daily_briefing",
                "sections": ["weather", "news"],
                "location": "广州",
                "triggered_at": "2026-08-10T08:00:00",
                "speak": True,
            },
        }
        with patch(
            "core.providers.tools.device_mcp.mcp_handler.build_daily_briefing",
            AsyncMock(return_value="简报内容"),
        ) as build, patch(
            "core.providers.tools.device_mcp.mcp_handler._speak_proactive_notification",
            AsyncMock(return_value="briefing-turn"),
        ) as speak, patch(
            "core.providers.tools.device_mcp.mcp_handler.capture_netease_briefing_resume",
            Mock(return_value="resume-token"),
        ) as capture, patch(
            "core.providers.tools.device_mcp.mcp_handler.schedule_netease_briefing_resume",
            Mock(),
        ) as schedule_resume:
            await handle_mcp_message(conn, MCPClient(), payload)
            await handle_mcp_message(conn, MCPClient(), payload)
        build.assert_awaited_once()
        self.assertEqual((conn, ["weather", "news"], "广州"), build.await_args.args)
        self.assertEqual([], build.await_args.kwargs["suggestion_topics"])
        capture.assert_called_once_with(conn, 0)
        speak.assert_awaited_once_with(
            conn,
            "简报内容",
            "每日简报",
            None,
            completion_event=ANY,
        )
        completion_event = speak.await_args.kwargs["completion_event"]
        schedule_resume.assert_called_once_with(
            conn,
            "resume-token",
            "briefing-turn",
            completion_event,
        )

    async def test_reminder_notification_does_not_schedule_music_resume(self):
        conn = SimpleNamespace(sentence_id="old", abort_generation=3)
        payload = {
            "method": "notifications/schedule/triggered",
            "params": {
                "version": 1,
                "id": 18,
                "kind": "reminder",
                "label": "喝水",
                "triggered_at": "2026-08-10T08:00:00",
                "speak": True,
            },
        }
        with patch(
            "core.providers.tools.device_mcp.mcp_handler._speak_proactive_notification",
            AsyncMock(),
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.capture_netease_briefing_resume",
            Mock(),
        ) as capture, patch(
            "core.providers.tools.device_mcp.mcp_handler.schedule_netease_briefing_resume",
            Mock(),
        ) as schedule_resume:
            await handle_mcp_message(conn, MCPClient(), payload)

        capture.assert_not_called()
        schedule_resume.assert_not_called()

    async def test_reminder_invites_completion_confirmation_but_alarm_does_not(self):
        conn = SimpleNamespace(sentence_id="old", abort_generation=3)
        base = {
            "version": 1,
            "id": 18,
            "label": "吃药",
            "triggered_at": "2026-08-10T08:00:00",
            "speak": True,
        }
        with patch(
            "core.providers.tools.device_mcp.mcp_handler._speak_proactive_notification",
            AsyncMock(),
        ) as speak, patch(
            "core.providers.tools.device_mcp.mcp_handler.claim_proactive_opportunity",
            Mock(return_value=True),
        ):
            await handle_mcp_message(
                conn,
                MCPClient(),
                {"method": "notifications/schedule/triggered", "params": {**base, "kind": "reminder"}},
            )
            await handle_mcp_message(
                conn,
                MCPClient(),
                {"method": "notifications/schedule/triggered", "params": {**base, "id": 19, "kind": "alarm"}},
            )

        reminder_call, alarm_call = speak.await_args_list
        self.assertIn("处理完告诉我一声", reminder_call.args[1])
        self.assertIsNone(reminder_call.kwargs["text_transform"])
        self.assertIsNone(alarm_call.kwargs["text_transform"])

    async def test_rejects_event_id_that_does_not_match_occurrence(self):
        conn = SimpleNamespace(sentence_id="old", abort_generation=0)
        payload = {
            "method": "notifications/assistant/triggered",
            "params": {
                "version": 1,
                "id": 17,
                "event_id": "17-20260810T090000",
                "workflow": "daily_briefing",
                "sections": ["weather"],
                "location": "广州",
                "triggered_at": "2026-08-10T08:00:00",
                "speak": True,
            },
        }
        with patch(
            "core.providers.tools.device_mcp.mcp_handler.build_daily_briefing",
            AsyncMock(),
        ) as build:
            await handle_mcp_message(conn, MCPClient(), payload)
        build.assert_not_awaited()

    async def test_rejects_non_string_section_without_raising(self):
        conn = SimpleNamespace(sentence_id="old", abort_generation=0)
        payload = {
            "method": "notifications/assistant/triggered",
            "params": {
                "version": 1,
                "id": 17,
                "event_id": "17-20260810T080000",
                "workflow": "daily_briefing",
                "sections": [{"name": "weather"}],
                "location": "广州",
                "triggered_at": "2026-08-10T08:00:00",
                "speak": True,
            },
        }
        with patch(
            "core.providers.tools.device_mcp.mcp_handler.build_daily_briefing",
            AsyncMock(),
        ) as build:
            await handle_mcp_message(conn, MCPClient(), payload)
        build.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
