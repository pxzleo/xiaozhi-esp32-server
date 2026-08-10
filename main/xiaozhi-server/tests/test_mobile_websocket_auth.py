from types import SimpleNamespace
import asyncio

import pytest
from websockets.datastructures import Headers

import core.websocket_server as websocket_server
import core.connection as connection_module
from core.connection import ConnectionHandler
from core.mobile_protocol import MobileProtocolError


class FakeWebSocket:
    def __init__(self, headers):
        self.request = SimpleNamespace(headers=headers, path="/mobile/assistant")


class CapturingWebSocket:
    def __init__(self):
        self.sent = []

    async def send(self, message):
        self.sent.append(message)

    async def close(self, code=None):
        self.close_code = code


def mark_mobile_runtime_ready(conn):
    conn.bind_completed_event = asyncio.Event()
    conn.bind_completed_event.set()
    conn.components_ready_event = asyncio.Event()
    conn.components_ready_event.set()


def test_mobile_handshake_maps_authorized_instance_to_existing_device_identity(monkeypatch):
    captured = {}

    async def authorize(instance_id, installation_id, token, credential_version, capabilities):
        captured.update(
            instance_id=instance_id,
            installation_id=installation_id,
            token=token,
            credential_version=credential_version,
            capabilities=capabilities,
        )
        return {
            "authorized": True,
            "mobile_instance_id": instance_id,
            "agent_id": "agent-1",
            "credential_version": credential_version,
        }

    monkeypatch.setattr(websocket_server, "authorize_mobile_instance", authorize)
    server = websocket_server.WebSocketServer.__new__(websocket_server.WebSocketServer)
    instance_id = "mob_0123456789abcdef0123456789abcdef"
    installation_id = "123e4567-e89b-12d3-a456-426614174000"
    ws = FakeWebSocket({
        "mobile-instance-id": instance_id,
        "client-id": installation_id,
        "mobile-protocol-version": "1",
        "mobile-credential-version": "3",
        "mobile-capabilities": "text_chat,voice_session",
        "authorization": "Bearer opaque",
    })

    context = asyncio.run(server._prepare_mobile_connection(ws))

    assert captured["token"] == "opaque"
    assert ws.request.headers["device-id"] == instance_id
    assert ws.request.headers["client-id"] == installation_id
    assert context["capabilities"] == ["text_chat", "voice_session"]
    assert context["credential_version"] == 3
    assert "opaque" not in ws.request.headers["authorization"]


def test_mobile_handshake_replaces_client_id_without_duplicate_header(monkeypatch):
    async def authorize(instance_id, installation_id, token, credential_version, capabilities):
        return {
            "authorized": True,
            "mobile_instance_id": instance_id,
            "credential_version": credential_version,
        }

    monkeypatch.setattr(websocket_server, "authorize_mobile_instance", authorize)
    server = websocket_server.WebSocketServer.__new__(websocket_server.WebSocketServer)
    instance_id = "mob_0123456789abcdef0123456789abcdef"
    installation_id = "123e4567-e89b-12d3-a456-426614174000"
    headers = Headers([
        ("Mobile-Instance-Id", instance_id),
        ("Client-Id", installation_id),
        ("Mobile-Protocol-Version", "1"),
        ("Mobile-Credential-Version", "3"),
        ("Mobile-Capabilities", "text_chat,voice_session"),
        ("Authorization", "Bearer opaque"),
    ])
    ws = FakeWebSocket(headers)

    asyncio.run(server._prepare_mobile_connection(ws))

    assert headers.get_all("client-id") == [installation_id]
    assert dict(headers)["client-id"] == installation_id


def test_mobile_handshake_rejects_revoked_credential(monkeypatch):
    async def denied(*args):
        return {"authorized": False}

    monkeypatch.setattr(websocket_server, "authorize_mobile_instance", denied)
    server = websocket_server.WebSocketServer.__new__(websocket_server.WebSocketServer)
    ws = FakeWebSocket({
        "mobile-instance-id": "mob_0123456789abcdef0123456789abcdef",
        "client-id": "123e4567-e89b-12d3-a456-426614174000",
        "mobile-protocol-version": "1",
        "mobile-credential-version": "3",
        "mobile-capabilities": "text_chat",
        "authorization": "Bearer revoked",
    })

    with pytest.raises(MobileProtocolError, match="已撤销"):
        asyncio.run(server._prepare_mobile_connection(ws))


def test_mobile_handshake_rejects_client_supplied_hardware_identity():
    server = websocket_server.WebSocketServer.__new__(websocket_server.WebSocketServer)
    ws = FakeWebSocket({
        "mobile-instance-id": "mob_0123456789abcdef0123456789abcdef",
        "client-id": "123e4567-e89b-12d3-a456-426614174000",
        "device-id": "hardware-id-must-not-be-accepted",
        "mobile-protocol-version": "1",
        "mobile-credential-version": "3",
        "mobile-capabilities": "text_chat",
        "authorization": "Bearer opaque",
    })

    with pytest.raises(MobileProtocolError, match="硬件设备"):
        asyncio.run(server._prepare_mobile_connection(ws))


def test_mobile_handshake_rejects_unknown_mobile_header():
    server = websocket_server.WebSocketServer.__new__(websocket_server.WebSocketServer)
    ws = FakeWebSocket({
        "mobile-instance-id": "mob_0123456789abcdef0123456789abcdef",
        "client-id": "123e4567-e89b-12d3-a456-426614174000",
        "mobile-protocol-version": "1",
        "mobile-credential-version": "3",
        "mobile-capabilities": "text_chat",
        "mobile-tts-text": "forbidden",
        "authorization": "Bearer opaque",
    })

    with pytest.raises(MobileProtocolError, match="未知字段"):
        asyncio.run(server._prepare_mobile_connection(ws))


def test_reserved_mobile_identity_is_rejected_on_device_path_even_without_device_auth():
    server = websocket_server.WebSocketServer.__new__(websocket_server.WebSocketServer)
    ws = CapturingWebSocket()
    ws.request = SimpleNamespace(
        headers={"device-id": "MOB_0123456789ABCDEF0123456789ABCDEF"},
        path="/xiaozhi/v1",
    )

    asyncio.run(server._handle_connection(ws))

    assert ws.close_code == 4401
    assert '"code": "MOBILE_PATH_REQUIRED"' in ws.sent[0]


def test_text_message_is_acknowledged_only_after_existing_handler_and_durable_complete(monkeypatch):
    routed = []

    async def claim(instance_id, message_id):
        return {"status": "claimed", "claim_token": "a" * 32}

    async def complete(instance_id, message_id, claim_token):
        assert routed
        return True

    async def authorized(self, force=False):
        return True

    async def route(conn, message):
        routed.append(message)

    monkeypatch.setattr(connection_module, "claim_mobile_message", claim)
    monkeypatch.setattr(connection_module, "complete_mobile_message", complete)
    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    monkeypatch.setattr(connection_module, "handleTextMessage", route)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.device_id = "mob_0123456789abcdef0123456789abcdef"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()
    conn.mobile_capabilities = {"text_chat", "voice_session"}
    conn.mobile_protocol_state = "READY"
    mark_mobile_runtime_ready(conn)
    conn.need_bind = False

    asyncio.run(conn._route_message(
        '{"type":"listen","version":1,"state":"detect","text":"你好","message_id":"msg_01"}'
    ))

    assert '"status": "accepted"' in conn.websocket.sent[0]
    assert len(routed) == 1


def test_duplicate_text_message_is_acknowledged_without_second_turn(monkeypatch):
    async def duplicate(instance_id, message_id):
        return {"status": "duplicate", "claim_token": None}

    async def authorized(self, force=False):
        return True

    async def must_not_route(conn, message):
        raise AssertionError("duplicate message reached assistant engine")

    monkeypatch.setattr(connection_module, "claim_mobile_message", duplicate)
    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    monkeypatch.setattr(connection_module, "handleTextMessage", must_not_route)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.device_id = "mob_0123456789abcdef0123456789abcdef"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()
    conn.mobile_capabilities = {"text_chat", "voice_session"}
    conn.mobile_protocol_state = "READY"
    mark_mobile_runtime_ready(conn)
    conn.need_bind = False

    asyncio.run(conn._route_message(
        '{"type":"listen","version":1,"state":"detect","text":"你好","message_id":"msg_01"}'
    ))

    assert '"status": "duplicate"' in conn.websocket.sent[0]


def test_text_frame_requires_bound_text_capability(monkeypatch):
    async def must_not_claim(instance_id, message_id):
        raise AssertionError("unbound capability reached message service")

    async def authorized(self, force=False):
        return True

    monkeypatch.setattr(connection_module, "claim_mobile_message", must_not_claim)
    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.mobile_capabilities = {"voice_session"}
    conn.device_id = "mob_0123456789abcdef0123456789abcdef"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()
    conn.mobile_protocol_state = "READY"

    asyncio.run(conn._route_message(
        '{"type":"listen","version":1,"state":"detect","text":"你好","message_id":"msg_01"}'
    ))

    assert '"code": "CAPABILITY_REQUIRED"' in conn.websocket.sent[0]
    assert conn.websocket.close_code == 4400


def test_business_frame_before_hello_is_rejected(monkeypatch):
    async def authorized(self, force=False):
        return True

    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.mobile_capabilities = {"text_chat"}
    conn.mobile_protocol_state = "AWAIT_HELLO"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()

    asyncio.run(conn._route_message(
        '{"type":"listen","version":1,"state":"detect","text":"你好","message_id":"msg_01"}'
    ))

    assert '"code": "HELLO_REQUIRED"' in conn.websocket.sent[0]
    assert conn.websocket.close_code == 4400


def test_duplicate_hello_is_rejected(monkeypatch):
    async def authorized(self, force=False):
        return True

    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.mobile_capabilities = {"text_chat"}
    conn.mobile_protocol_state = "READY"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()

    asyncio.run(conn._route_message(
        '{"type":"hello","version":1,"transport":"websocket",'
        '"audio_params":{"format":"opus","sample_rate":24000,"channels":1,'
        '"frame_duration":60},"features":{"aec":true}}'
    ))

    assert '"code": "DUPLICATE_HELLO"' in conn.websocket.sent[0]
    assert conn.websocket.close_code == 4400


def test_handler_failure_keeps_message_claim_without_accepted_ack(monkeypatch):

    async def authorized(self, force=False):
        return True

    async def claim(instance_id, message_id):
        return {"status": "claimed", "claim_token": "a" * 32}

    async def fail(conn, message):
        raise RuntimeError("handler failed")

    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    monkeypatch.setattr(connection_module, "claim_mobile_message", claim)
    monkeypatch.setattr(connection_module, "handleTextMessage", fail)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.mobile_capabilities = {"text_chat"}
    conn.mobile_protocol_state = "READY"
    conn.device_id = "mob_0123456789abcdef0123456789abcdef"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()
    mark_mobile_runtime_ready(conn)
    conn.need_bind = False

    with pytest.raises(RuntimeError, match="handler failed"):
        asyncio.run(conn._route_message(
            '{"type":"listen","version":1,"state":"detect","text":"你好","message_id":"msg_01"}'
        ))

    assert not conn.websocket.sent


def test_complete_failure_keeps_claim_and_does_not_ack(monkeypatch):

    async def authorized(self, force=False):
        return True

    async def claim(instance_id, message_id):
        return {"status": "claimed", "claim_token": "a" * 32}

    async def route(conn, message):
        return None

    async def fail_complete(instance_id, message_id, claim_token):
        raise RuntimeError("manager unavailable")

    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    monkeypatch.setattr(connection_module, "claim_mobile_message", claim)
    monkeypatch.setattr(connection_module, "handleTextMessage", route)
    monkeypatch.setattr(connection_module, "complete_mobile_message", fail_complete)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.mobile_capabilities = {"text_chat"}
    conn.mobile_protocol_state = "READY"
    conn.device_id = "mob_0123456789abcdef0123456789abcdef"
    conn.session_id = "session"
    conn.logger = SimpleNamespace(bind=lambda **kwargs: SimpleNamespace(error=lambda message: None))
    conn.websocket = CapturingWebSocket()
    mark_mobile_runtime_ready(conn)
    conn.need_bind = False

    with pytest.raises(RuntimeError, match="manager unavailable"):
        asyncio.run(conn._route_message(
            '{"type":"listen","version":1,"state":"detect","text":"你好",'
            '"message_id":"msg_01"}'
        ))

    assert conn.websocket.sent == []
    assert conn.websocket.close_code == 1011


def test_text_handler_runs_with_active_lease_renewal(monkeypatch):
    renewal_started = asyncio.Event()
    renewal_cancelled = asyncio.Event()

    async def authorized(self, force=False):
        return True

    async def claim(instance_id, message_id):
        return {"status": "claimed", "claim_token": "a" * 32}

    async def renew_forever(self, message_id, claim_token):
        renewal_started.set()
        try:
            await asyncio.Future()
        finally:
            renewal_cancelled.set()

    async def route(conn, message):
        await renewal_started.wait()

    async def complete(instance_id, message_id, claim_token):
        return True

    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    monkeypatch.setattr(ConnectionHandler, "_renew_mobile_message_lease", renew_forever)
    monkeypatch.setattr(connection_module, "claim_mobile_message", claim)
    monkeypatch.setattr(connection_module, "handleTextMessage", route)
    monkeypatch.setattr(connection_module, "complete_mobile_message", complete)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.client_kind = "mobile"
    conn.mobile_capabilities = {"text_chat"}
    conn.mobile_protocol_state = "READY"
    conn.device_id = "mob_0123456789abcdef0123456789abcdef"
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()
    mark_mobile_runtime_ready(conn)
    conn.need_bind = False

    asyncio.run(conn._route_message(
        '{"type":"listen","version":1,"state":"detect","text":"你好",'
        '"message_id":"msg_01"}'
    ))

    assert renewal_cancelled.is_set()
    assert '"status": "accepted"' in conn.websocket.sent[0]


def test_mobile_text_waits_for_component_readiness(monkeypatch):
    routed = []

    async def authorized(self, force=False):
        return True

    async def route(self, message, payload):
        routed.append(message)

    async def scenario():
        conn = ConnectionHandler.__new__(ConnectionHandler)
        conn.client_kind = "mobile"
        conn.mobile_capabilities = {"text_chat"}
        conn.mobile_protocol_state = "READY"
        conn.device_id = "mob_0123456789abcdef0123456789abcdef"
        conn.session_id = "session"
        conn.websocket = CapturingWebSocket()
        conn.bind_completed_event = asyncio.Event()
        conn.bind_completed_event.set()
        conn.components_ready_event = asyncio.Event()
        conn.need_bind = False
        task = asyncio.create_task(conn._route_message(
            '{"type":"listen","version":1,"state":"detect","text":"你好",'
            '"message_id":"msg_01"}'
        ))
        await asyncio.sleep(0)
        assert routed == []
        conn.components_ready_event.set()
        await task

    monkeypatch.setattr(ConnectionHandler, "_ensure_mobile_authorized", authorized)
    monkeypatch.setattr(ConnectionHandler, "_route_mobile_text_message", route)
    asyncio.run(scenario())

    assert len(routed) == 1


def test_established_connection_closes_when_credential_is_revoked(monkeypatch):
    async def denied(*args):
        return {"authorized": False}

    monkeypatch.setattr(connection_module, "authorize_mobile_instance", denied)
    conn = ConnectionHandler.__new__(ConnectionHandler)
    conn.mobile_last_auth_check = 0.0
    conn.mobile_auth_context = {
        "instance_id": "mob_0123456789abcdef0123456789abcdef",
        "installation_id": "123e4567-e89b-12d3-a456-426614174000",
        "token": "opaque",
        "credential_version": 3,
        "capabilities": ["voice_session"],
    }
    conn.session_id = "session"
    conn.websocket = CapturingWebSocket()

    assert asyncio.run(conn._ensure_mobile_authorized(force=True)) is False
    assert conn.websocket.close_code == 4401
    assert '"code": "UNAUTHORIZED"' in conn.websocket.sent[0]
