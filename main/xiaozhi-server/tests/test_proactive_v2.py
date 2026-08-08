import asyncio
import importlib
import json
import queue
import threading
import time
import unittest
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from config import manage_api_client
from core.connection import ConnectionHandler
from core.providers.tools.device_mcp import mcp_handler
from core.providers.tools.device_mcp.daily_briefing import weather_action_suggestion
from core.providers.tools.device_mcp.proactive_habits import (
    observe_habit_and_maybe_suggest,
    suggest_habit_candidate,
)
from core.providers.tools.device_mcp.proactive_policy import (
    reset_proactive_policy_for_test,
    safe_local_preferences,
)
from core.providers.tts.base import _complete_playback_event
from plugins_func.functions.play_netease_music import _late_night_music_suggestion


def _event_fields(**overrides):
    now = int(time.time())
    data = {
        "event_id": "event-1",
        "topic": "follow_up",
        "priority": "normal",
        "reason": "schedule follow up",
        "created_at": now - 10,
        "expires_at": now + 3600,
        "dedupe_key": "dedupe-1",
        "requires_response": True,
    }
    data.update(overrides)
    return data


def _discard_delivery_audit(_conn, _audit, delivery_result):
    if hasattr(delivery_result, "close"):
        delivery_result.close()


async def _completed_speech(conn, _text, _name, _state=None, **kwargs):
    conn.sentence_id = "sid"
    conn.abort_generation = getattr(conn, "abort_generation", 0)
    conn.client_abort = False
    completion = kwargs.get("completion_event")
    if completion is not None:
        completion.set_result(True)
    return "sid"


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
        ) as speak, patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock(return_value=False)
        ), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(
            mcp_handler, "_schedule_delivery_audit", side_effect=_discard_delivery_audit
        ) as audit:
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
        speak.assert_awaited_once()
        self.assertEqual("刚才提醒的喝水完成了吗？", speak.await_args.args[1])
        audit.assert_called_once()

    async def test_critical_health_manager_claim_has_one_cross_connection_winner(self):
        params = {
            **_event_fields(
                topic="health_critical", priority="critical",
                reason="device health", requires_response=False,
            ),
            "version": 1,
            "kind": "audio_decode_failed",
            "severity": "critical",
            "occurred_at": 1_786_170_600,
            "recovered": False,
            "details": {"error_code": "17"},
        }
        connections = [SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences=safe_local_preferences(),
        ) for _ in range(2)]
        claim_lock = asyncio.Lock()
        claimed = False

        async def atomic_claim(_event_id, _mac_address, _claim_token):
            nonlocal claimed
            async with claim_lock:
                if claimed:
                    return False
                claimed = True
                return True

        speak = AsyncMock(return_value="sid")
        with patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock(return_value=False)
        ), patch.object(
            mcp_handler, "claim_proactive_event", side_effect=atomic_claim
        ), patch.object(
            mcp_handler, "_speak_proactive_notification", speak
        ), patch.object(
            mcp_handler, "_schedule_delivery_audit", side_effect=_discard_delivery_audit
        ):
            await asyncio.gather(*(
                mcp_handler._handle_device_health_notification(conn, params)
                for conn in connections
            ))
        speak.assert_awaited_once()

    async def test_critical_health_manager_outage_keeps_safety_delivery(self):
        params = {
            **_event_fields(
                topic="health_critical", priority="critical",
                reason="device health", requires_response=False,
            ),
            "version": 1,
            "kind": "audio_decode_failed",
            "severity": "critical",
            "occurred_at": 1_786_170_600,
            "recovered": False,
            "details": {"error_code": "17"},
        }
        speak = AsyncMock(return_value="sid")
        captured = {}

        async def uncertain_claim(_event_id, _mac_address, claim_token):
            captured["claim_token"] = claim_token
            raise RuntimeError("response lost")

        def capture_audit(_conn, audit, delivery_result):
            captured["audit"] = audit
            if hasattr(delivery_result, "close"):
                delivery_result.close()

        with patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock(return_value=False)
        ), patch.object(
            mcp_handler, "claim_proactive_event", side_effect=uncertain_claim
        ), patch.object(
            mcp_handler, "_speak_proactive_notification", speak
        ), patch.object(
            mcp_handler, "_schedule_delivery_audit", side_effect=capture_audit
        ):
            await mcp_handler._handle_device_health_notification(self.conn, params)
        speak.assert_awaited_once()
        self.assertEqual(
            captured["claim_token"], captured["audit"]["_claim_token"]
        )
        status = AsyncMock()
        with patch.object(
            mcp_handler, "create_proactive_event",
            AsyncMock(return_value={"delivery_status": "claimed"}),
        ), patch.object(mcp_handler, "update_proactive_event_status", status):
            self.assertTrue(await mcp_handler._audit_proactive_delivery(
                captured["audit"], False
            ))
        self.assertEqual(captured["claim_token"], status.await_args.kwargs["claim_token"])

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
        ) as speak, patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock(return_value=False)
        ), patch.object(
            mcp_handler, "claim_proactive_event", AsyncMock(return_value=True)
        ), patch.object(
            mcp_handler, "_schedule_delivery_audit", side_effect=_discard_delivery_audit
        ):
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

    async def test_follow_up_rejects_noncanonical_priority(self):
        params = {
            **_event_fields(priority="high"),
            "version": 1,
            "follow_up": True,
            "source_id": 7,
            "label": "喝水",
            "speak": True,
        }
        with patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock()
        ) as speak:
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
        speak.assert_not_awaited()

    async def test_long_device_event_id_uses_manager_safe_id(self):
        params = {
            **_event_fields(event_id="e" * 96),
            "version": 1,
            "follow_up": True,
            "source_id": 7,
            "label": "喝水",
            "speak": True,
        }
        with patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock(return_value="sid")
        ), patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock(return_value=False)
        ), patch.object(
            mcp_handler, "_schedule_delivery_audit", side_effect=_discard_delivery_audit
        ):
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
        self.assertLessEqual(len(self.conn._current_followup_audit_event_id), 64)
        self.assertNotEqual(params["event_id"], self.conn._current_followup_audit_event_id)

    async def test_successful_proactive_tool_syncs_preference_with_one_retry(self):
        preference = {
            "mode": "active",
            "daily_limit": 2,
            "quiet_start": "22:30",
            "quiet_end": "07:00",
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
        self.conn._current_followup_audit_event_id = "event-1"
        self.conn._current_followup_source_id = 7
        self.conn._current_followup_audit_task = asyncio.create_task(
            asyncio.sleep(0, result=True)
        )
        with patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as update:
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.complete_recent",
                {"action": "RESPONSE", "data": {"source_id": 7}},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        update.assert_awaited_once_with(
            "event-1", "AA:BB", "delivered", "completed"
        )

    async def test_successful_schedule_dismiss_uses_real_tool_name(self):
        self.conn._current_followup_audit_event_id = "event-1"
        self.conn._current_followup_source_id = 7
        self.conn._current_followup_audit_task = asyncio.create_task(
            asyncio.sleep(0, result=True)
        )
        with patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as update:
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.dismiss_follow_up",
                {"action": "RESPONSE", "data": {"source_id": 7}},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        update.assert_awaited_once_with(
            "event-1", "AA:BB", "dismissed", "dismissed"
        )

    async def test_stale_connection_preference_load_cannot_overwrite_device_change(self):
        logger = Mock()
        logger.bind.return_value = logger
        conn = SimpleNamespace(
            device_id="AA:BB",
            logger=logger,
            proactive_preferences=safe_local_preferences(),
            _proactive_preference_revision=0,
        )
        started = asyncio.Event()
        release = asyncio.Event()

        async def delayed_preference(_mac_address):
            started.set()
            await release.wait()
            return {
                **safe_local_preferences(),
                "mode": "aggressive",
                "daily_limit": 5,
            }

        with patch("core.connection.get_proactive_preference", delayed_preference):
            task = asyncio.create_task(ConnectionHandler._load_proactive_preferences(conn))
            await started.wait()
            conn.proactive_preferences = {
                **safe_local_preferences(),
                "mode": "conservative",
                "daily_limit": 1,
            }
            conn._proactive_preference_revision += 1
            release.set()
            await task
        self.assertEqual("conservative", conn.proactive_preferences["mode"])

    async def test_stale_connection_preference_failure_cannot_reset_device_change(self):
        logger = Mock()
        logger.bind.return_value = logger
        conn = SimpleNamespace(
            device_id="AA:BB",
            logger=logger,
            proactive_preferences=safe_local_preferences(),
            _proactive_preference_revision=0,
        )
        started = asyncio.Event()
        release = asyncio.Event()

        async def delayed_failure(_mac_address):
            started.set()
            await release.wait()
            raise RuntimeError("manager offline")

        with patch("core.connection.get_proactive_preference", delayed_failure):
            task = asyncio.create_task(ConnectionHandler._load_proactive_preferences(conn))
            await started.wait()
            conn.proactive_preferences = {
                **safe_local_preferences(),
                "mode": "conservative",
                "daily_limit": 1,
            }
            conn._proactive_preference_revision += 1
            release.set()
            await task
        self.assertEqual("conservative", conn.proactive_preferences["mode"])

    async def test_follow_up_outcome_waits_for_delivery_audit(self):
        calls = []

        async def create_event(_event):
            calls.append("created")
            await asyncio.sleep(0)
            return {"delivery_status": "pending"}

        async def update_status(_event_id, _mac, delivery_status, outcome):
            calls.append((delivery_status, outcome))

        event = _event_fields()
        with patch.object(
            mcp_handler, "create_proactive_event", side_effect=create_event
        ), patch.object(
            mcp_handler, "update_proactive_event_status", side_effect=update_status
        ):
            audit = mcp_handler._build_proactive_audit(
                self.conn,
                event,
                {"title": "完成跟进", "reference_id": "7", "source": "device"},
            )
            audit_task = mcp_handler._schedule_delivery_audit(self.conn, audit, True)
            self.conn._current_followup_audit_event_id = "event-1"
            self.conn._current_followup_source_id = 7
            self.conn._current_followup_audit_task = audit_task
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.complete_recent",
                {"action": "RESPONSE", "data": {"source_id": 7}},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        self.assertEqual(
            ["created", ("delivered", "none"), ("delivered", "completed")],
            calls,
        )

    async def test_delivery_audit_waits_for_real_tts_completion(self):
        completion = mcp_handler.ProactiveDeliveryCompletion()
        self.conn.sentence_id = "sid"
        self.conn.abort_generation = 3
        self.conn.client_abort = False
        audit = mcp_handler._build_proactive_audit(
            self.conn,
            _event_fields(),
            {"title": "完成跟进", "reference_id": "7", "source": "device"},
        )
        status = AsyncMock()
        with patch.object(
            mcp_handler,
            "create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch.object(mcp_handler, "update_proactive_event_status", status):
            task = mcp_handler._schedule_delivery_audit(
                self.conn,
                audit,
                mcp_handler._wait_for_proactive_delivery(
                    self.conn, completion, "sid", 3
                ),
            )
            await asyncio.sleep(0)
            status.assert_not_awaited()
            completion.set_result(True)
            self.assertTrue(await task)
        status.assert_awaited_once_with("event-1", "AA:BB", "delivered", "none")

    async def test_claimed_delivery_status_uses_token_without_leaking_into_event(self):
        audit = mcp_handler._build_proactive_audit(
            self.conn,
            _event_fields(topic="health_critical", priority="critical"),
            {"title": "设备健康", "reference_id": "audio", "source": "device"},
        )
        audit["_claim_token"] = "claim-token"
        create = AsyncMock(return_value={"delivery_status": "claimed"})
        status = AsyncMock()
        with patch.object(mcp_handler, "create_proactive_event", create), patch.object(
            mcp_handler, "update_proactive_event_status", status
        ):
            self.assertTrue(await mcp_handler._audit_proactive_delivery(audit, True))
        self.assertNotIn("_claim_token", create.await_args.args[0])
        status.assert_awaited_once_with(
            "event-1", "AA:BB", "delivered", "none", claim_token="claim-token"
        )

    async def test_abort_before_tts_completion_is_failed(self):
        completion = mcp_handler.ProactiveDeliveryCompletion()
        self.conn.sentence_id = "sid"
        self.conn.abort_generation = 3
        self.conn.client_abort = True
        delivered = await mcp_handler._wait_for_proactive_delivery(
            self.conn, completion, "sid", 3
        )
        self.assertFalse(delivered)

    async def test_failed_completion_is_not_delivered(self):
        completion = mcp_handler.ProactiveDeliveryCompletion()
        completion.set_result(False)
        self.conn.sentence_id = "sid"
        self.conn.abort_generation = 3
        self.conn.client_abort = False
        delivered = await mcp_handler._wait_for_proactive_delivery(
            self.conn, completion, "sid", 3
        )
        self.assertFalse(delivered)

    async def test_expired_follow_up_is_rejected_before_audit_or_speech(self):
        params = {
            **_event_fields(expires_at=int(time.time()) - 1),
            "version": 1,
            "follow_up": True,
            "source_id": 7,
            "label": "喝水",
            "speak": True,
        }
        with patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock()
        ) as prepare, patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock()
        ) as speak:
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
        prepare.assert_not_awaited()
        speak.assert_not_awaited()

    def test_notification_dedupe_uses_bounded_fifo_eviction(self):
        for index in range(mcp_handler.MAX_SEEN_NOTIFICATION_EVENTS + 1):
            self.assertTrue(mcp_handler._claim_notification_event(self.conn, f"e-{index}"))
        self.assertFalse(mcp_handler._claim_notification_event(self.conn, "e-1"))
        self.assertTrue(mcp_handler._claim_notification_event(self.conn, "e-0"))
        self.assertLessEqual(
            len(self.conn._proactive_notification_events),
            mcp_handler.MAX_SEEN_NOTIFICATION_EVENTS,
        )

    async def test_manager_delivered_event_blocks_cross_connection_replay(self):
        params = {
            **_event_fields(),
            "version": 1,
            "follow_up": True,
            "source_id": 7,
            "label": "喝水",
            "speak": True,
        }
        with patch.object(
            mcp_handler, "_prepare_proactive_audit", AsyncMock(return_value=True)
        ), patch.object(
            mcp_handler, "_speak_proactive_notification", AsyncMock()
        ) as speak:
            await mcp_handler._handle_schedule_follow_up_notification(self.conn, params)
        speak.assert_not_awaited()

    async def test_follow_up_outcome_rejects_mismatched_source_id(self):
        self.conn._current_followup_audit_event_id = "event-1"
        self.conn._current_followup_source_id = 7
        self.conn._current_followup_audit_task = asyncio.create_task(
            asyncio.sleep(0, result=True)
        )
        with patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as update:
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.complete_recent",
                {"action": "RESPONSE", "data": {"source_id": 8}},
            )
            await self.conn._current_followup_audit_task
        update.assert_not_awaited()

    async def test_follow_up_outcome_stops_when_initial_audit_failed(self):
        audit = mcp_handler._build_proactive_audit(
            self.conn,
            _event_fields(),
            {"title": "完成跟进", "reference_id": "7", "source": "device"},
        )
        with patch.object(
            mcp_handler,
            "create_proactive_event",
            AsyncMock(side_effect=RuntimeError("offline")),
        ), patch.object(
            mcp_handler, "update_proactive_event_status", AsyncMock()
        ) as update:
            self.conn._current_followup_audit_event_id = "event-1"
            self.conn._current_followup_source_id = 7
            self.conn._current_followup_audit_task = (
                mcp_handler._schedule_delivery_audit(self.conn, audit, True)
            )
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.complete_recent",
                {"action": "RESPONSE", "data": {"source_id": 7}},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        update.assert_not_awaited()

    async def test_follow_up_outcome_stops_when_delivery_status_audit_failed(self):
        audit = mcp_handler._build_proactive_audit(
            self.conn,
            _event_fields(),
            {"title": "完成跟进", "reference_id": "7", "source": "device"},
        )
        update = AsyncMock(side_effect=RuntimeError("status offline"))
        with patch.object(
            mcp_handler,
            "create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch.object(mcp_handler, "update_proactive_event_status", update):
            self.conn._current_followup_audit_event_id = "event-1"
            self.conn._current_followup_source_id = 7
            self.conn._current_followup_audit_task = (
                mcp_handler._schedule_delivery_audit(self.conn, audit, True)
            )
            mcp_handler.handle_successful_device_tool_result(
                self.conn,
                "self.schedule.complete_recent",
                {"action": "RESPONSE", "data": {"source_id": 7}},
            )
            await asyncio.gather(*self.conn._proactive_background_tasks)
        self.assertEqual(1, update.await_count)

    async def test_connection_cleanup_cancels_background_tasks(self):
        started = asyncio.Event()

        async def pending():
            started.set()
            await asyncio.Event().wait()

        background = asyncio.create_task(pending())
        habit = asyncio.create_task(pending())
        audit = asyncio.create_task(asyncio.sleep(0, result=False))
        await started.wait()
        conn = SimpleNamespace(
            _proactive_audit_tasks={audit},
            _proactive_background_tasks={background},
            _proactive_habit_tasks={habit},
        )
        await ConnectionHandler._finish_and_cancel_proactive_tasks(conn)
        self.assertTrue(background.cancelled())
        self.assertTrue(habit.cancelled())
        self.assertTrue(audit.done())

    async def test_connection_close_marks_waiting_delivery_failed(self):
        completion = mcp_handler.ProactiveDeliveryCompletion()
        closed = asyncio.Event()
        conn = SimpleNamespace(
            device_id="AA:BB",
            sentence_id="sid",
            abort_generation=1,
            client_abort=False,
            connection_closed_event=closed,
            _proactive_audit_tasks=set(),
            _proactive_background_tasks=set(),
            _proactive_habit_tasks=set(),
        )
        audit = mcp_handler._build_proactive_audit(
            conn,
            _event_fields(),
            {"title": "完成跟进", "reference_id": "7", "source": "device"},
        )
        status = AsyncMock()
        with patch.object(
            mcp_handler,
            "create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch.object(mcp_handler, "update_proactive_event_status", status):
            mcp_handler._schedule_delivery_audit(
                conn,
                audit,
                mcp_handler._wait_for_proactive_delivery(conn, completion, "sid", 1),
            )
            closed.set()
            await ConnectionHandler._finish_and_cancel_proactive_tasks(conn)
        status.assert_awaited_once_with("event-1", "AA:BB", "failed", "failed")
        completion.set_result(True)

    async def test_close_during_speech_after_prepare_writes_failed(self):
        started = asyncio.Event()

        async def blocked_speech(*_args, **_kwargs):
            started.set()
            await asyncio.Event().wait()

        conn = SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences=safe_local_preferences(),
            sentence_id="old",
            abort_generation=1,
            client_abort=False,
            connection_closed_event=asyncio.Event(),
            _proactive_audit_tasks=set(),
            _proactive_background_tasks=set(),
            _proactive_habit_tasks=set(),
            _proactive_delivery_futures=set(),
        )
        params = {
            **_event_fields(),
            "version": 1,
            "follow_up": True,
            "source_id": 7,
            "label": "喝水",
            "speak": True,
        }
        status = AsyncMock()
        with patch.object(
            mcp_handler,
            "create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ) as create, patch.object(
            mcp_handler, "update_proactive_event_status", status
        ), patch.object(
            mcp_handler, "_speak_proactive_notification", side_effect=blocked_speech
        ):
            notification = asyncio.create_task(
                mcp_handler._handle_schedule_follow_up_notification(conn, params)
            )
            conn._proactive_background_tasks.add(notification)
            await started.wait()
            conn.connection_closed_event.set()
            await ConnectionHandler._finish_and_cancel_proactive_tasks(conn)
        self.assertTrue(notification.cancelled())
        self.assertEqual(2, create.await_count)
        status.assert_awaited_once_with("event-1", "AA:BB", "failed", "failed")

    async def test_reminder_invitation_speech_error_is_audited_failed(self):
        conn = SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences=safe_local_preferences(),
            sentence_id="old",
            abort_generation=0,
            client_abort=False,
        )
        params = {
            "version": 1,
            "id": 7,
            "kind": "reminder",
            "label": "喝水",
            "triggered_at": "2026-08-10T08:00:00",
            "speak": True,
        }
        status = AsyncMock()
        with patch.object(
            mcp_handler, "claim_proactive_opportunity", return_value=True
        ), patch.object(
            mcp_handler,
            "_speak_proactive_notification",
            AsyncMock(side_effect=RuntimeError("tts failed")),
        ), patch(
            "core.providers.tools.device_mcp.proactive_audit.create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch(
            "core.providers.tools.device_mcp.proactive_audit.update_proactive_event_status",
            status,
        ):
            with self.assertRaises(RuntimeError):
                await mcp_handler._handle_schedule_triggered_notification(conn, params)
            await asyncio.gather(*conn._proactive_audit_tasks)
        self.assertEqual("failed", status.await_args.args[2])

    async def test_weather_suggestion_cancel_is_audited_failed(self):
        conn = SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences=safe_local_preferences(),
            sentence_id="old",
            abort_generation=0,
            client_abort=False,
        )
        params = {
            "version": 1,
            "id": 17,
            "event_id": "17-20260810T080000",
            "triggered_at": "2026-08-10T08:00:00",
            "workflow": "daily_briefing",
            "sections": ["weather"],
            "location": "广州",
            "speak": True,
        }

        async def briefing(_conn, _sections, _location, suggestion_topics=None):
            suggestion_topics.append("weather")
            return "天气简报。记得带伞。"

        status = AsyncMock()
        with patch.object(
            mcp_handler, "build_daily_briefing", side_effect=briefing
        ), patch.object(
            mcp_handler, "capture_netease_briefing_resume", return_value=None
        ), patch.object(
            mcp_handler,
            "_speak_proactive_notification",
            AsyncMock(side_effect=asyncio.CancelledError),
        ), patch(
            "core.providers.tools.device_mcp.proactive_audit.create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch(
            "core.providers.tools.device_mcp.proactive_audit.update_proactive_event_status",
            status,
        ):
            with self.assertRaises(asyncio.CancelledError):
                await mcp_handler._handle_assistant_triggered_notification(conn, params)
            await asyncio.gather(*conn._proactive_audit_tasks)
        self.assertEqual("failed", status.await_args.args[2])

    async def test_xunfei_monitor_rejects_server_error_and_disconnect(self):
        module = importlib.import_module("core.providers.tts.xunfei_stream")

        class FakeWebSocket:
            def __init__(self, response):
                self.response = response

            async def recv(self):
                if isinstance(self.response, list):
                    response = self.response.pop(0)
                    if isinstance(response, BaseException):
                        raise response
                    return response
                if isinstance(self.response, BaseException):
                    raise self.response
                return self.response

            async def close(self):
                return None

        def provider_for(response):
            provider = module.TTSProvider.__new__(module.TTSProvider)
            provider.conn = SimpleNamespace(
                stop_event=SimpleNamespace(is_set=lambda: False),
                client_abort=False,
                sentence_id="sid",
            )
            provider.ws = FakeWebSocket(response)
            provider.activate_session = True
            provider._monitor_task = asyncio.current_task()
            provider._sentence_text_map = {}
            return provider

        error_provider = provider_for(json.dumps({
            "header": {"code": 101, "message": "synthesis failed"}
        }))
        with self.assertRaisesRegex(RuntimeError, "101"):
            await error_provider._start_monitor_tts_response("sid")
        self.assertIn("sid", error_provider._sentence_completion_failures)

        class FakeClosed(Exception):
            pass

        disconnected = provider_for(FakeClosed("closed"))
        with patch.object(module.websockets, "ConnectionClosed", FakeClosed):
            with self.assertRaisesRegex(RuntimeError, "WebSocket连接已关闭"):
                await disconnected._start_monitor_tts_response("sid")
        self.assertIn("sid", disconnected._sentence_completion_failures)

        damaged = provider_for([
            json.dumps({
                "header": {"code": 0},
                "payload": {"audio": {"status": 1, "audio": "a"}},
            }),
            json.dumps({
                "header": {"code": 0},
                "payload": {"audio": {"status": 2, "audio": ""}},
            }),
        ])
        damaged.opus_encoder = Mock()
        damaged.handle_opus = Mock()
        with self.assertRaisesRegex(RuntimeError, "处理TTS音频数据失败"):
            await damaged._start_monitor_tts_response("sid")
        self.assertIn("sid", damaged._sentence_completion_failures)

    async def test_xunfei_cancel_old_monitor_does_not_fail_new_sentence(self):
        module = importlib.import_module("core.providers.tts.xunfei_stream")
        waiting = asyncio.Event()

        class BlockingWebSocket:
            async def recv(self):
                waiting.set()
                await asyncio.Event().wait()

            async def close(self):
                return None

        provider = module.TTSProvider.__new__(module.TTSProvider)
        provider.conn = SimpleNamespace(
            stop_event=SimpleNamespace(is_set=lambda: False),
            client_abort=False,
            sentence_id="old-sid",
        )
        provider.ws = BlockingWebSocket()
        provider.activate_session = True
        provider._monitor_task = None
        task = asyncio.create_task(
            provider._start_monitor_tts_response("old-sid")
        )
        provider.conn.sentence_id = "new-sid"
        await waiting.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertNotIn(
            "new-sid", getattr(provider, "_sentence_completion_failures", set())
        )

    async def test_xunfei_missing_socket_cannot_finish_as_success(self):
        module = importlib.import_module("core.providers.tts.xunfei_stream")
        provider = module.TTSProvider.__new__(module.TTSProvider)
        provider.ws = None
        provider.activate_session = True
        provider._monitor_task = None
        provider.conn = SimpleNamespace()
        with patch.object(provider, "close", AsyncMock()):
            with self.assertRaisesRegex(RuntimeError, "WebSocket连接不存在"):
                await provider.finish_session("sid")

    def test_all_tts_thread_overrides_forward_completion_contract(self):
        provider_files = (
            "alibl_stream.py",
            "aliyun_stream.py",
            "huoshan_double_stream.py",
            "index_stream.py",
            "minimax_httpstream.py",
            "xunfei_stream.py",
        )
        root = Path(__file__).parents[1] / "core/providers/tts"
        for filename in provider_files:
            source = (root / filename).read_text(encoding="utf-8")
            self.assertTrue(
                "_forward_sentence_completion(message)" in source
                or "_defer_remote_sentence_completion(message)" in source,
                filename,
            )
            self.assertIn("_mark_sentence_completion_failed(message)", source, filename)

    def test_all_tts_thread_overrides_forward_middle_completion(self):
        module_names = (
            "alibl_stream",
            "aliyun_stream",
            "huoshan_double_stream",
            "index_stream",
            "minimax_httpstream",
            "xunfei_stream",
        )

        class StopAfterOne:
            def __init__(self):
                self.calls = 0

            def is_set(self):
                self.calls += 1
                return self.calls > 1

        for module_name in module_names:
            provider_class = importlib.import_module(
                f"core.providers.tts.{module_name}"
            ).TTSProvider
            provider = provider_class.__new__(provider_class)
            provider.conn = SimpleNamespace(
                stop_event=StopAfterOne(), client_abort=False, sentence_id="sid"
            )
            provider.tts_text_queue = queue.Queue()
            provider.tts_audio_queue = queue.Queue()
            provider.text_seq = 0
            completion = threading.Event()
            provider.tts_text_queue.put(
                mcp_handler.TTSMessageDTO(
                    sentence_id="sid",
                    sentence_type=mcp_handler.SentenceType.MIDDLE,
                    content_type=mcp_handler.ContentType.ACTION,
                    completion_event=completion,
                )
            )
            provider.tts_text_priority_thread()
            if module_name in {
                "alibl_stream",
                "aliyun_stream",
                "huoshan_double_stream",
            }:
                self.assertTrue(provider.tts_audio_queue.empty(), module_name)
                provider._complete_remote_sentence("sid")
            forwarded = provider.tts_audio_queue.get_nowait()
            self.assertIs(forwarded[4], completion, module_name)

    def test_remote_completion_is_queued_after_received_audio_and_failure_is_direct(self):
        from core.providers.tts.base import TTSProviderBase

        class ConcreteProvider(TTSProviderBase):
            async def text_to_speak(self, text, output_file):
                return None

        provider = ConcreteProvider.__new__(ConcreteProvider)
        provider.conn = SimpleNamespace(loop=None)
        provider.tts_audio_queue = queue.Queue()
        success = threading.Event()
        message = mcp_handler.TTSMessageDTO(
            sentence_id="sid",
            sentence_type=mcp_handler.SentenceType.MIDDLE,
            content_type=mcp_handler.ContentType.ACTION,
            completion_event=success,
        )
        provider._defer_remote_sentence_completion(message)
        provider.tts_audio_queue.put((mcp_handler.SentenceType.MIDDLE, b"audio", None, "sid"))
        provider._complete_remote_sentence("sid")
        self.assertEqual(b"audio", provider.tts_audio_queue.get_nowait()[1])
        self.assertIs(success, provider.tts_audio_queue.get_nowait()[4])

        failed = mcp_handler.ProactiveDeliveryCompletion()
        message.completion_event = failed
        provider._defer_remote_sentence_completion(message)
        provider._complete_remote_sentence("sid", succeeded=False)
        self.assertTrue(failed.is_set())
        self.assertFalse(failed.succeeded)

    def test_remote_sentence_completion_keeps_fifo_placeholders_and_session_separate(self):
        from core.providers.tts.base import TTSProviderBase

        class ConcreteProvider(TTSProviderBase):
            async def text_to_speak(self, text, output_file):
                return None

        provider = ConcreteProvider.__new__(ConcreteProvider)
        provider.conn = SimpleNamespace(loop=None)
        provider.tts_audio_queue = queue.Queue()
        first = mcp_handler.TTSMessageDTO(
            sentence_id="sid",
            sentence_type=mcp_handler.SentenceType.MIDDLE,
            content_type=mcp_handler.ContentType.TEXT,
            content_detail="前一段。",
        )
        segment_completion = threading.Event()
        second = mcp_handler.TTSMessageDTO(
            sentence_id="sid",
            sentence_type=mcp_handler.SentenceType.MIDDLE,
            content_type=mcp_handler.ContentType.TEXT,
            content_detail="后一段。",
            completion_event=segment_completion,
        )
        session_completion = threading.Event()
        last = mcp_handler.TTSMessageDTO(
            sentence_id="sid",
            sentence_type=mcp_handler.SentenceType.LAST,
            content_type=mcp_handler.ContentType.ACTION,
            completion_event=session_completion,
        )
        for message in (first, second, last):
            provider._defer_remote_sentence_completion(message)

        provider._complete_remote_sentence("sid")
        self.assertTrue(provider.tts_audio_queue.empty())
        self.assertFalse(segment_completion.is_set())
        self.assertFalse(session_completion.is_set())

        provider._complete_remote_sentence("sid")
        self.assertIs(
            segment_completion, provider.tts_audio_queue.get_nowait()[4]
        )
        provider._complete_remote_sentence("sid")
        self.assertTrue(provider.tts_audio_queue.empty())
        self.assertFalse(session_completion.is_set())

        provider._complete_remote_sentence("sid", all_pending=True)
        self.assertIs(
            session_completion, provider.tts_audio_queue.get_nowait()[4]
        )

    def test_remote_completion_stays_failed_after_an_earlier_segment_failure(self):
        from core.providers.tts.base import TTSProviderBase

        class ConcreteProvider(TTSProviderBase):
            async def text_to_speak(self, text, output_file):
                return None

        provider = ConcreteProvider.__new__(ConcreteProvider)
        provider.conn = SimpleNamespace(loop=None)
        provider.tts_audio_queue = queue.Queue()
        first = mcp_handler.TTSMessageDTO(
            sentence_id="sid",
            sentence_type=mcp_handler.SentenceType.MIDDLE,
            content_type=mcp_handler.ContentType.TEXT,
            content_detail="失败前段",
        )
        completion = mcp_handler.ProactiveDeliveryCompletion()
        second = mcp_handler.TTSMessageDTO(
            sentence_id="sid",
            sentence_type=mcp_handler.SentenceType.MIDDLE,
            content_type=mcp_handler.ContentType.TEXT,
            content_detail="成功后段",
            completion_event=completion,
        )
        provider._defer_remote_sentence_completion(first)
        provider._mark_sentence_completion_failed(first)
        provider._defer_remote_sentence_completion(second)

        provider._complete_remote_sentence("sid", succeeded=True)

        self.assertTrue(completion.is_set())
        self.assertFalse(completion.succeeded)
        self.assertTrue(provider.tts_audio_queue.empty())

    def test_bidirectional_providers_execute_plain_segment_before_completed_segment(self):
        class StopAfterTwo:
            def __init__(self):
                self.calls = 0

            def is_set(self):
                self.calls += 1
                return self.calls > 2

        class CompletedFuture:
            def result(self, timeout=None):
                return None

        def complete_immediately(coro, loop=None):
            coro.close()
            return CompletedFuture()

        for module_name in (
            "alibl_stream",
            "aliyun_stream",
            "huoshan_double_stream",
        ):
            module = importlib.import_module(f"core.providers.tts.{module_name}")
            provider = module.TTSProvider.__new__(module.TTSProvider)
            provider.conn = SimpleNamespace(
                stop_event=StopAfterTwo(),
                client_abort=False,
                sentence_id="sid",
                loop=object(),
            )
            provider.tts_text_queue = queue.Queue()
            provider.tts_audio_queue = queue.Queue()
            provider.tts_timeout = 1
            completion = threading.Event()
            provider.tts_text_queue.put(mcp_handler.TTSMessageDTO(
                sentence_id="sid",
                sentence_type=mcp_handler.SentenceType.MIDDLE,
                content_type=mcp_handler.ContentType.TEXT,
                content_detail="前一段。",
            ))
            provider.tts_text_queue.put(mcp_handler.TTSMessageDTO(
                sentence_id="sid",
                sentence_type=mcp_handler.SentenceType.MIDDLE,
                content_type=mcp_handler.ContentType.TEXT,
                content_detail="后一段。",
                completion_event=completion,
            ))

            with patch.object(
                module.asyncio,
                "run_coroutine_threadsafe",
                side_effect=complete_immediately,
            ):
                provider.tts_text_priority_thread()

            provider._complete_remote_sentence("sid")
            self.assertTrue(provider.tts_audio_queue.empty(), module_name)
            provider._complete_remote_sentence("sid")
            self.assertIs(
                completion, provider.tts_audio_queue.get_nowait()[4], module_name
            )

    def test_tts_one_sentence_keeps_terminal_punctuation_with_completion_segment(self):
        from core.providers.tts.base import TTSProviderBase

        class ConcreteProvider(TTSProviderBase):
            async def text_to_speak(self, text, output_file):
                return None

        provider = ConcreteProvider.__new__(ConcreteProvider)
        provider.tts_text_queue = queue.Queue()
        completion = threading.Event()
        conn = SimpleNamespace(sentence_id="sid")

        provider.tts_one_sentence(
            conn,
            mcp_handler.ContentType.TEXT,
            content_detail="我来处理一下。",
            completion_event=completion,
        )

        message = provider.tts_text_queue.get_nowait()
        self.assertEqual("我来处理一下。", message.content_detail)
        self.assertIs(completion, message.completion_event)
        self.assertTrue(provider.tts_text_queue.empty())
        provider.conn = SimpleNamespace(loop=None)
        provider.tts_audio_queue = queue.Queue()
        provider._defer_remote_sentence_completion(message)
        provider._complete_remote_sentence("sid")
        self.assertIs(completion, provider.tts_audio_queue.get_nowait()[4])

    async def test_late_tts_future_completion_after_cancel_is_thread_safe(self):
        loop = asyncio.get_running_loop()
        completion = loop.create_future()
        completion.cancel()
        await asyncio.to_thread(
            _complete_playback_event,
            SimpleNamespace(loop=loop),
            completion,
            True,
        )
        await asyncio.sleep(0)
        self.assertTrue(completion.cancelled())

    async def test_tts_future_completion_uses_event_loop_thread(self):
        loop = asyncio.get_running_loop()
        completion = loop.create_future()
        worker_id = None

        def complete_from_worker():
            nonlocal worker_id
            worker_id = threading.get_ident()
            _complete_playback_event(SimpleNamespace(loop=loop), completion, True)

        await asyncio.to_thread(complete_from_worker)
        self.assertTrue(await completion)
        self.assertNotEqual(worker_id, threading.get_ident())


class ManageApiProactiveClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_strict_wrapper_uses_expected_endpoint_without_logging_payload(self):
        client = SimpleNamespace(_execute_async_request=AsyncMock(return_value={"mode": "active"}))
        with patch.object(manage_api_client.ManageApiClient, "_instance", client):
            await manage_api_client.get_proactive_preference("AA:BB")
            await manage_api_client.create_proactive_event({"event_id": "x"})
            await manage_api_client.update_proactive_event_status(
                "x", "AA:BB", "delivered"
            )
            client._execute_async_request.return_value = True
            self.assertTrue(await manage_api_client.claim_proactive_event(
                "x", "AA:BB", "claim-token"
            ))
            client._execute_async_request.return_value = {"habit_key": "x"}
            await manage_api_client.observe_proactive_habit({"habit_key": "x"})
            client._execute_async_request.return_value = []
            await manage_api_client.get_proactive_habit_candidates("AA:BB")
        calls = client._execute_async_request.await_args_list
        self.assertEqual("GET", calls[0].args[0])
        self.assertIn("preferences/AA%3ABB", calls[0].args[1])
        self.assertEqual("POST", calls[1].args[0])
        self.assertEqual("PUT", calls[2].args[0])
        self.assertEqual("POST", calls[3].args[0])
        self.assertIn("/claim", calls[3].args[1])
        self.assertEqual("POST", calls[4].args[0])
        self.assertEqual("GET", calls[5].args[0])
        for call in calls:
            self.assertEqual(0.5, call.kwargs["timeout"])
            self.assertEqual(1, call.kwargs["_max_retries"])
            self.assertEqual(0.1, call.kwargs["_retry_delay"])


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

    def test_weather_negation_does_not_suggest_rain_action(self):
        self.assertEqual("", weather_action_suggestion(self.conn, "广州今天没有雨。"))
        self.assertEqual("", weather_action_suggestion(self.conn, "广州今天无雨。"))
        self.assertEqual("", weather_action_suggestion(self.conn, "广州今天不会有雨。"))
        self.assertEqual("", weather_action_suggestion(self.conn, "广州今天没有阵雨。"))


class HabitSuggestionTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        reset_proactive_policy_for_test()

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
            "core.providers.tools.device_mcp.mcp_handler.create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.update_proactive_event_status",
            AsyncMock(),
        ) as status, patch.object(
            mcp_handler, "_speak_proactive_notification", side_effect=_completed_speech
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

    async def test_candidate_audit_is_identical_across_connections(self):
        candidate = {
            "evidence_count": 3,
            "habit_key": "music:category:abc",
            "habit_type": "content_preference",
            "first_seen_at": 1_786_170_600_000,
        }
        events = []

        async def record_event(event):
            events.append(event)
            return {"delivery_status": "pending"}

        with patch(
            "core.providers.tools.device_mcp.proactive_habits.create_proactive_event",
            side_effect=record_event,
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.create_proactive_event",
            side_effect=record_event,
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.update_proactive_event_status",
            AsyncMock(),
        ), patch.object(
            mcp_handler, "_speak_proactive_notification", side_effect=_completed_speech
        ):
            for _index in range(2):
                reset_proactive_policy_for_test()
                conn = SimpleNamespace(
                    device_id="AA:BB",
                    headers={"device-id": "AA:BB"},
                    proactive_preferences=safe_local_preferences(),
                )
                self.assertTrue(await suggest_habit_candidate(conn, candidate))
        self.assertEqual(events[0], events[1])

    async def test_delivered_candidate_does_not_consume_daily_budget(self):
        conn = SimpleNamespace(
            device_id="AA:BB",
            headers={"device-id": "AA:BB"},
            proactive_preferences={
                **safe_local_preferences(),
                "daily_limit": 1,
            },
        )

        async def existing_status(event):
            status = (
                "delivered"
                if event["payload"]["reference_id"] == "music:category:old"
                else "pending"
            )
            return {"delivery_status": status}

        base = {
            "evidence_count": 3,
            "habit_type": "content_preference",
            "first_seen_at": 1_786_170_600_000,
        }
        with patch(
            "core.providers.tools.device_mcp.proactive_habits.create_proactive_event",
            side_effect=existing_status,
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.create_proactive_event",
            AsyncMock(return_value={"delivery_status": "pending"}),
        ), patch(
            "core.providers.tools.device_mcp.mcp_handler.update_proactive_event_status",
            AsyncMock(),
        ), patch.object(
            mcp_handler, "_speak_proactive_notification", side_effect=_completed_speech
        ) as speak:
            old_result = await suggest_habit_candidate(
                conn, {**base, "habit_key": "music:category:old"}
            )
            new_result = await suggest_habit_candidate(
                conn, {**base, "habit_key": "music:category:new"}
            )
        self.assertFalse(old_result)
        self.assertTrue(new_result)
        speak.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
