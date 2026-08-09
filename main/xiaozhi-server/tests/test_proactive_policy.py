import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from types import SimpleNamespace

from core.providers.tools.device_mcp import proactive_policy

from core.providers.tools.device_mcp.proactive_policy import (
    claim_proactive_opportunity,
    policy_allows,
    reserve_proactive_opportunity_with_reason,
    reset_proactive_policy_for_test,
    set_connection_preferences,
)


class ProactivePolicyTest(unittest.TestCase):
    def setUp(self):
        reset_proactive_policy_for_test()
        self.conn = SimpleNamespace(headers={"device-id": "device-a"})

    def test_topic_cooldown_blocks_repeated_suggestion(self):
        self.assertTrue(
            claim_proactive_opportunity(
                self.conn, "music_continue", cooldown_seconds=60, now=100
            )
        )
        self.assertFalse(
            claim_proactive_opportunity(
                self.conn, "music_continue", cooldown_seconds=60, now=120
            )
        )
        self.assertTrue(
            claim_proactive_opportunity(
                self.conn, "music_continue", cooldown_seconds=60, now=161
            )
        )

    def test_reservation_reports_stable_cooldown_and_daily_limit_reasons(self):
        first = reserve_proactive_opportunity_with_reason(
            self.conn, "external_news", cooldown_seconds=60, daily_limit=1, now=100
        )
        cooldown = reserve_proactive_opportunity_with_reason(
            self.conn, "external_news", cooldown_seconds=60, daily_limit=1, now=120
        )
        budget = reserve_proactive_opportunity_with_reason(
            self.conn, "weather", cooldown_seconds=0, daily_limit=1, now=161
        )

        self.assertIsNotNone(first.reservation)
        self.assertIsNone(first.rejection_reason)
        self.assertIsNone(cooldown.reservation)
        self.assertEqual("topic_cooldown", cooldown.rejection_reason)
        self.assertIsNone(budget.reservation)
        self.assertEqual("daily_limit", budget.rejection_reason)

    def test_daily_limit_is_shared_across_connections_for_device(self):
        other = SimpleNamespace(headers={"device-id": "device-a"})
        for index in range(3):
            self.assertTrue(
                claim_proactive_opportunity(
                    self.conn,
                    f"topic-{index}",
                    cooldown_seconds=0,
                    daily_limit=3,
                    now=100 + index,
                )
            )
        self.assertFalse(
            claim_proactive_opportunity(
                other,
                "topic-4",
                cooldown_seconds=0,
                daily_limit=3,
                now=200,
            )
        )

    def test_new_day_resets_budget(self):
        for index in range(3):
            claim_proactive_opportunity(
                self.conn,
                f"topic-{index}",
                cooldown_seconds=0,
                now=100 + index,
            )
        self.assertTrue(
            claim_proactive_opportunity(
                self.conn,
                "next-day",
                cooldown_seconds=0,
                now=24 * 3600 + 100,
            )
        )

    def test_aggressive_topic_cooldown_survives_midnight(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "aggressive",
                "daily_limit": 0,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        before_midnight = datetime(2026, 8, 8, 23, 59, 50).timestamp()
        self.assertTrue(
            claim_proactive_opportunity(
                self.conn, "weather", cooldown_seconds=120, now=before_midnight
            )
        )
        self.assertFalse(
            claim_proactive_opportunity(
                self.conn, "weather", cooldown_seconds=120, now=before_midnight + 20
            )
        )
        self.assertTrue(
            claim_proactive_opportunity(
                self.conn, "weather", cooldown_seconds=120, now=before_midnight + 121
            )
        )

    def test_new_day_resets_active_budget_without_dropping_cooldown(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "active",
                "daily_limit": 2,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        before_midnight = datetime(2026, 8, 8, 23, 59, 40).timestamp()
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "calendar", cooldown_seconds=120, now=before_midnight))
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "system", cooldown_seconds=120, now=before_midnight + 10))
        self.assertFalse(claim_proactive_opportunity(
            self.conn, "calendar", cooldown_seconds=120, now=before_midnight + 30))
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "music", cooldown_seconds=120, now=before_midnight + 31))
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "weather", cooldown_seconds=120, now=before_midnight + 32))
        self.assertFalse(claim_proactive_opportunity(
            self.conn, "habit", cooldown_seconds=120, now=before_midnight + 33))

    def test_expired_unique_topics_are_reclaimed_across_many_days(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "aggressive",
                "daily_limit": 0,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        first_day = datetime(2026, 8, 8, 12, 0).timestamp()
        for day in range(5):
            current = first_day + day * 24 * 3600
            for index in range(200):
                self.assertTrue(claim_proactive_opportunity(
                    self.conn,
                    f"dynamic-{day}-{index}",
                    cooldown_seconds=3600,
                    now=current,
                ))
            state = proactive_policy._states["device-a"]
            self.assertEqual(200, len(state.topic_deadlines))

    def test_each_topic_uses_its_own_cooldown_deadline(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "aggressive",
                "daily_limit": 0,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        now = datetime(2026, 8, 8, 12, 0).timestamp()
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "short", cooldown_seconds=10, now=now))
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "long", cooldown_seconds=100, now=now))
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "short", cooldown_seconds=10, now=now + 11))
        self.assertFalse(claim_proactive_opportunity(
            self.conn, "long", cooldown_seconds=10, now=now + 11))
        self.assertTrue(claim_proactive_opportunity(
            self.conn, "long", cooldown_seconds=10, now=now + 101))

    def test_mock_like_device_attribute_does_not_become_shared_key(self):
        first = SimpleNamespace(headers={})
        second = SimpleNamespace(headers={})
        self.assertTrue(
            claim_proactive_opportunity(
                first, "topic", cooldown_seconds=60, now=100
            )
        )
        self.assertTrue(
            claim_proactive_opportunity(
                second, "topic", cooldown_seconds=60, now=100
            )
        )

    def test_clock_rollback_does_not_reset_newer_day_budget(self):
        for index in range(5):
            claim_proactive_opportunity(
                self.conn,
                f"new-day-{index}",
                cooldown_seconds=0,
                now=24 * 3600 + 100 + index,
            )
        self.assertFalse(
            claim_proactive_opportunity(
                self.conn,
                "old-day-call",
                cooldown_seconds=0,
                now=100,
            )
        )

    def test_preferences_apply_mode_topics_quiet_and_limit(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "active",
                "daily_limit": 1,
                "quiet_start": "22:00",
                "quiet_end": "07:00",
                "allowed_topics": ["music"],
                "blocked_topics": [],
            },
        )
        noon = datetime(2026, 8, 8, 12, 0).timestamp()
        night = datetime(2026, 8, 8, 23, 0).timestamp()
        self.assertTrue(policy_allows(self.conn, "music", now=noon))
        self.assertFalse(policy_allows(self.conn, "weather", now=noon))
        self.assertFalse(policy_allows(self.conn, "music", now=night))
        self.assertTrue(
            claim_proactive_opportunity(
                self.conn, "one", cooldown_seconds=0, policy_topic="music", now=noon
            )
        )
        self.assertFalse(
            claim_proactive_opportunity(
                self.conn, "two", cooldown_seconds=0, policy_topic="music", now=noon + 1
            )
        )

    def test_aggressive_has_no_daily_budget_but_keeps_other_policy_gates(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "aggressive",
                "daily_limit": 0,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        for index in range(6):
            self.assertTrue(
                claim_proactive_opportunity(
                    self.conn,
                    f"aggressive-{index}",
                    cooldown_seconds=60,
                    now=100 + index,
                )
            )
        self.assertFalse(
            claim_proactive_opportunity(
                self.conn, "aggressive-5", cooldown_seconds=60, now=106
            )
        )

    def test_active_defaults_to_five_and_rejects_sixth_claim(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "active",
                "daily_limit": 5,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        for index in range(5):
            self.assertTrue(
                claim_proactive_opportunity(
                    self.conn, f"active-{index}", cooldown_seconds=0, now=100 + index
                )
            )
        self.assertFalse(
            claim_proactive_opportunity(
                self.conn, "active-5", cooldown_seconds=0, now=106
            )
        )

    def test_conservative_only_allows_critical(self):
        set_connection_preferences(
            self.conn,
            {
                "mode": "conservative",
                "daily_limit": 1,
                "quiet_start": None,
                "quiet_end": None,
                "allowed_topics": [],
                "blocked_topics": [],
            },
        )
        self.assertFalse(policy_allows(self.conn, "health"))
        self.assertTrue(policy_allows(self.conn, "health", critical=True))

    def test_concurrent_claim_has_single_winner(self):
        with ThreadPoolExecutor(max_workers=8) as executor:
            results = list(
                executor.map(
                    lambda _index: claim_proactive_opportunity(
                        self.conn,
                        "same-topic",
                        cooldown_seconds=60,
                        daily_limit=5,
                        now=100,
                    ),
                    range(8),
                )
            )
        self.assertEqual(1, sum(results))


if __name__ == "__main__":
    unittest.main()
