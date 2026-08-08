import unittest
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from core.providers.tools.device_mcp.daily_briefing import build_daily_briefing
from core.providers.tools.device_mcp.mcp_handler import (
    MCPClient,
    handle_mcp_message,
)


class DailyBriefingTest(unittest.IsolatedAsyncioTestCase):
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
            AsyncMock(),
        ) as speak:
            await handle_mcp_message(conn, MCPClient(), payload)
            await handle_mcp_message(conn, MCPClient(), payload)
        build.assert_awaited_once_with(conn, ["weather", "news"], "广州")
        speak.assert_awaited_once_with(conn, "简报内容", "每日简报", None)

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
