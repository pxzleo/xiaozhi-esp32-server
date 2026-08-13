import unittest
import json
import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

from core.providers.tools.device_mcp import mcp_handler


class ScheduleDeviceSyncTest(unittest.IsolatedAsyncioTestCase):
    async def test_call_mcp_tool_send_failure_cleans_registered_future(self):
        client = mcp_handler.MCPClient()
        await client.add_tool({
            "name": "self.schedule.list", "description": "list",
            "inputSchema": {"type": "object", "properties": {}},
        })
        await client.set_ready(True)
        conn = SimpleNamespace(
            features={"mcp": True},
            websocket=SimpleNamespace(send=AsyncMock(side_effect=OSError("closed"))),
        )

        with self.assertRaises(OSError):
            await mcp_handler.call_mcp_tool(conn,client,"self_schedule_list")

        self.assertEqual({},client.call_results)

    async def test_uuid_occurrence_trigger_carries_current_requester_mac(self):
        schedule_uuid = "4d8b9aec-1e54-4d3e-a9f4-c37a7f21c995"
        conn = SimpleNamespace(
            device_id="AA:BB", common_config={"server":{"timezone_offset":8}},
        )
        trigger = AsyncMock(return_value={"id":schedule_uuid})
        with patch.object(mcp_handler,"trigger_shared_schedule",trigger), patch.object(
            mcp_handler,"_push_schedule_sync_once",AsyncMock()
        ):
            result = await mcp_handler._claim_shared_schedule_delivery(
                conn,source_schedule_id="1",schedule_uuid=schedule_uuid,
                occurrence_at=1_786_584_600,kind="reminder",label="喝水",
                triggered_at="2026-08-13T09:30:00",speak=False,
            )
        self.assertIsNone(result)
        trigger.assert_awaited_once_with(schedule_uuid,1_786_584_600_000,"AA:BB")

    async def test_create_rejects_label_beyond_eighty_code_points(self):
        conn = SimpleNamespace(
            device_id="AA:BB", common_config={"server":{"timezone_offset":8}},
            _proactive_background_tasks=set(),
        )
        register = AsyncMock()
        with patch.object(mcp_handler,"register_shared_schedule",register):
            mcp_handler.handle_successful_device_tool_result(
                conn,"self.schedule.create",{"action":"RESPONSE","data":{"task":{
                    "id":1,"kind":"reminder","repeat":"once",
                    "label":"字"*81,"trigger_at":"2026-08-13T09:30:00",
                    "weekdays":[],
                }}},
            )
            await asyncio.gather(*conn._proactive_background_tasks)
        register.assert_not_awaited()

    async def test_sync_uses_frozen_json_rpc_notification_and_waits_for_device_ack(self):
        conn = SimpleNamespace(
            device_id="AA:BB", features={"mcp": True}, websocket=SimpleNamespace(send=AsyncMock()),
            _schedule_sync_applied_revision=3, _schedule_sync_sent_revision=3,
        )
        response = {"protocol_version": 1, "full_snapshot": False, "cursor": 7,
                    "has_more": False, "changes": []}
        with patch.object(mcp_handler, "get_shared_schedule_sync", AsyncMock(return_value=response)):
            await mcp_handler._push_schedule_sync_once(conn)
        sent = conn.websocket.send.await_args.args[0]
        self.assertIn('"method": "notifications/schedule/sync"', sent)
        self.assertEqual(3, conn._schedule_sync_applied_revision)
        mcp_handler._handle_schedule_sync_applied(
            conn, {"version": 1, "through_revision": 7})
        self.assertEqual(7, conn._schedule_sync_applied_revision)

    async def test_action_ack_reaches_manager_only_after_device_applied(self):
        conn = SimpleNamespace(device_id="AA:BB", _schedule_action_sent_revision=9,
                               _schedule_action_applied_revision=4,
                               _schedule_action_pending_sent=True)
        ack = AsyncMock(return_value={"protocol_version": 1, "acked_revision": 9})
        with patch.object(mcp_handler, "acknowledge_shared_schedule_actions", ack):
            await mcp_handler._handle_schedule_action_applied(
                conn, {"version": 1, "through_revision": 9})
        ack.assert_awaited_once_with("AA:BB", 9)
        self.assertEqual(9, conn._schedule_action_applied_revision)

    async def test_rejects_ack_beyond_last_sent_revision(self):
        conn = SimpleNamespace(_schedule_sync_sent_revision=5,
                               _schedule_sync_applied_revision=0,
                               _schedule_sync_pending_sent=True)
        with self.assertRaises(ValueError):
            mcp_handler._handle_schedule_sync_applied(
                conn, {"version": 1, "through_revision": 6})

    async def test_sync_send_failure_retries_cached_page_successfully(self):
        conn = SimpleNamespace(
            device_id="AA:BB", features={"mcp": True},
            websocket=SimpleNamespace(send=AsyncMock(side_effect=[OSError("closed"),None])),
            _schedule_sync_applied_revision=0, _schedule_sync_sent_revision=0,
        )
        response = {"protocol_version": 1, "full_snapshot": True, "cursor": 4,
                    "has_more": False, "changes": []}
        fetch = AsyncMock(return_value=response)
        with patch.object(mcp_handler, "get_shared_schedule_sync", fetch):
            with self.assertRaises(OSError):
                await mcp_handler._push_schedule_sync_once(conn)
            self.assertEqual(0, conn._schedule_sync_sent_revision)
            self.assertFalse(conn._schedule_sync_pending_sent)
            await mcp_handler._push_schedule_sync_once(conn)
        self.assertEqual(4, conn._schedule_sync_sent_revision)
        self.assertTrue(conn._schedule_sync_pending_sent)
        fetch.assert_awaited_once_with("AA:BB",0,16)

    async def test_action_send_failure_retries_cached_action_successfully(self):
        action = {"protocol_version": 1, "revision": 5, "action": "stop"}
        conn = SimpleNamespace(
            device_id="AA:BB", features={"mcp": True},
            websocket=SimpleNamespace(send=AsyncMock(side_effect=[OSError("closed"),None])),
            _schedule_action_applied_revision=0, _schedule_action_sent_revision=0,
        )
        fetch = AsyncMock(return_value={
            "protocol_version": 1, "cursor": 5, "has_more": False,
            "actions": [action],
        })
        with patch.object(mcp_handler, "get_shared_schedule_actions", fetch):
            with self.assertRaises(OSError):
                await mcp_handler._push_schedule_actions_once(conn)
            self.assertEqual(0,conn._schedule_action_sent_revision)
            self.assertFalse(conn._schedule_action_pending_sent)
            await mcp_handler._push_schedule_actions_once(conn)
        self.assertEqual(5,conn._schedule_action_sent_revision)
        self.assertTrue(conn._schedule_action_pending_sent)
        fetch.assert_awaited_once_with("AA:BB",0,1)

    async def test_empty_initial_snapshot_is_replayed_when_ack_is_lost(self):
        conn = SimpleNamespace(
            device_id="AA:BB", features={"mcp": True},
            websocket=SimpleNamespace(send=AsyncMock()),
            _schedule_sync_applied_revision=0, _schedule_sync_sent_revision=0,
        )
        response = {"protocol_version": 1, "full_snapshot": True, "cursor": 0,
                    "has_more": False, "changes": []}
        fetch = AsyncMock(return_value=response)
        with patch.object(mcp_handler, "get_shared_schedule_sync", fetch):
            await mcp_handler._push_schedule_sync_once(conn)
            await mcp_handler._push_schedule_sync_once(conn)
        fetch.assert_awaited_once_with("AA:BB", 0, 16)
        self.assertEqual(2, conn.websocket.send.await_count)
        mcp_handler._handle_schedule_sync_applied(
            conn, {"version": 1, "through_revision": 0}
        )
        self.assertIsNone(conn._schedule_sync_pending_payload)

    async def test_sync_waits_for_first_page_ack_before_fetching_next_page(self):
        conn = SimpleNamespace(
            device_id="AA:BB", features={"mcp": True}, websocket=SimpleNamespace(send=AsyncMock()),
            _schedule_sync_applied_revision=0, _schedule_sync_sent_revision=0,
        )
        pages = [
            {"protocol_version": 1, "full_snapshot": True, "cursor": 4,
             "has_more": True, "changes": [{"revision": 4}]},
            {"protocol_version": 1, "full_snapshot": False, "cursor": 7,
             "has_more": False, "changes": [{"revision": 7}]},
        ]
        fetch = AsyncMock(side_effect=pages)
        with patch.object(mcp_handler, "get_shared_schedule_sync", fetch):
            await mcp_handler._push_schedule_sync_once(conn)
            await mcp_handler._push_schedule_sync_once(conn)
            self.assertEqual([unittest.mock.call("AA:BB", 0, 16)], fetch.await_args_list)
            resent = [json.loads(call.args[0])["payload"]
                      for call in conn.websocket.send.await_args_list]
            self.assertEqual([4, 4], [item["params"]["cursor"] for item in resent])
            mcp_handler._handle_schedule_sync_applied(
                conn, {"version": 1, "through_revision": 4})
            await mcp_handler._push_schedule_sync_once(conn)
        self.assertEqual([unittest.mock.call("AA:BB", 0, 16),
                          unittest.mock.call("AA:BB", 4, 16)], fetch.await_args_list)
        self.assertEqual(7, conn._schedule_sync_sent_revision)

    async def test_action_failure_cannot_be_skipped_by_later_action(self):
        first = {"protocol_version": 1, "revision": 5, "action": "stop"}
        second = {"protocol_version": 1, "revision": 8, "action": "delete"}
        conn = SimpleNamespace(
            device_id="AA:BB", features={"mcp": True},
            websocket=SimpleNamespace(send=AsyncMock()),
            _schedule_action_applied_revision=0, _schedule_action_sent_revision=0,
        )
        fetch = AsyncMock(side_effect=[
            {"protocol_version": 1, "cursor": 5, "has_more": True, "actions": [first]},
            {"protocol_version": 1, "cursor": 8, "has_more": False, "actions": [second]},
        ])
        with patch.object(mcp_handler, "get_shared_schedule_actions", fetch), patch.object(
            mcp_handler, "acknowledge_shared_schedule_actions",
            AsyncMock(return_value={"protocol_version": 1, "acked_revision": 5}),
        ):
            await mcp_handler._push_schedule_actions_once(conn)
            await mcp_handler._push_schedule_actions_once(conn)
            self.assertEqual([unittest.mock.call("AA:BB", 0, 1)], fetch.await_args_list)
            payloads = [json.loads(call.args[0])["payload"]["params"]
                        for call in conn.websocket.send.await_args_list]
            self.assertEqual([5, 5], [item["revision"] for item in payloads])
            with self.assertRaises(ValueError):
                await mcp_handler._handle_schedule_action_applied(
                    conn, {"version": 1, "through_revision": 8})
            await mcp_handler._handle_schedule_action_applied(
                conn, {"version": 1, "through_revision": 5})
            await mcp_handler._push_schedule_actions_once(conn)
        self.assertEqual([unittest.mock.call("AA:BB", 0, 1),
                          unittest.mock.call("AA:BB", 5, 1)], fetch.await_args_list)

    async def test_uuid_action_never_uses_current_device_source_id(self):
        schedule_uuid = "4d8b9aec-1e54-4d3e-a9f4-c37a7f21c995"
        conn = SimpleNamespace(device_id="AA:BB")
        with patch.object(mcp_handler, "action_shared_schedule", AsyncMock()) as by_uuid, \
                patch.object(mcp_handler, "action_shared_schedule_by_source", AsyncMock()) as by_source:
            await mcp_handler._sync_shared_schedule_action(
                conn, "1", "stop", schedule_uuid=schedule_uuid, local_id=99)
        by_uuid.assert_awaited_once_with(
            schedule_uuid, "stop", None, requester_mac_address="AA:BB"
        )
        by_source.assert_not_awaited()

    async def test_unbound_imported_schedule_cannot_use_by_source(self):
        conn = SimpleNamespace(device_id="AA:BB")
        with patch.object(mcp_handler, "action_shared_schedule_by_source", AsyncMock()) as by_source:
            with self.assertRaisesRegex(ValueError, "不是当前设备本地来源"):
                await mcp_handler._sync_shared_schedule_action(
                    conn, "1", "stop", local_id=99)
        by_source.assert_not_awaited()

    async def test_stop_result_prefers_uuid_action(self):
        schedule_uuid = "4d8b9aec-1e54-4d3e-a9f4-c37a7f21c995"
        conn = SimpleNamespace(device_id="AA:BB", _proactive_background_tasks=set())
        with patch.object(mcp_handler, "action_shared_schedule", AsyncMock()) as by_uuid, \
                patch.object(mcp_handler, "action_shared_schedule_by_source", AsyncMock()) as by_source:
            mcp_handler.handle_successful_device_tool_result(conn, "self.schedule.stop", {
                "action": "RESPONSE", "data": {
                    "stopped_id": 99, "source_schedule_id": "1",
                    "schedule_uuid": schedule_uuid,
                },
            })
            await asyncio.gather(*conn._proactive_background_tasks)
        by_uuid.assert_awaited_once_with(
            schedule_uuid, "stop", None, requester_mac_address="AA:BB"
        )
        by_source.assert_not_awaited()

    async def test_clear_result_uses_each_authoritative_uuid(self):
        first = "4d8b9aec-1e54-4d3e-a9f4-c37a7f21c995"
        second = "b79cecd0-1c67-47db-a676-fffc37e24f17"
        conn = SimpleNamespace(device_id="AA:BB", _proactive_background_tasks=set())
        with patch.object(mcp_handler, "action_shared_schedule", AsyncMock()) as by_uuid, \
                patch.object(mcp_handler, "action_shared_schedule_by_source", AsyncMock()) as by_source:
            mcp_handler.handle_successful_device_tool_result(conn, "self.schedule.clear", {
                "action": "RESPONSE", "data": {"deleted_tasks": [
                    {"id": 1, "source_schedule_id": "1", "schedule_uuid": first},
                    {"id": 2, "source_schedule_id": "88", "schedule_uuid": second},
                ]},
            })
            await asyncio.gather(*conn._proactive_background_tasks)
        self.assertEqual(
            {first, second},
            {call.args[0] for call in by_uuid.await_args_list},
        )
        by_source.assert_not_awaited()

    async def test_complete_recent_prefers_uuid_action(self):
        schedule_uuid = "4d8b9aec-1e54-4d3e-a9f4-c37a7f21c995"
        conn = SimpleNamespace(
            device_id="AA:BB", _proactive_background_tasks=set(),
            _current_followup_audit_event_id="event-1", _current_followup_source_id=99,
            _current_followup_audit_task=None,
        )
        with patch.object(
            mcp_handler, "_sync_followup_outcome_after_audit", AsyncMock()
        ), patch.object(mcp_handler, "action_shared_schedule", AsyncMock()) as by_uuid, \
                patch.object(mcp_handler, "action_shared_schedule_by_source", AsyncMock()) as by_source:
            mcp_handler.handle_successful_device_tool_result(
                conn, "self.schedule.complete_recent", {
                    "action": "RESPONSE", "data": {
                        "source_id": 99, "source_schedule_id": "1",
                        "schedule_uuid": schedule_uuid,
                    },
                },
            )
            await asyncio.gather(*conn._proactive_background_tasks)
        by_uuid.assert_awaited_once_with(
            schedule_uuid, "complete", None, requester_mac_address="AA:BB"
        )
        by_source.assert_not_awaited()
