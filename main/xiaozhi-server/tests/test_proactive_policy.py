import unittest
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from types import SimpleNamespace

from core.providers.tools.device_mcp.proactive_policy import (
    claim_proactive_opportunity,
    policy_allows,
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
        for index in range(3):
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
