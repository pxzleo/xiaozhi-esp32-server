import json

import pytest

from core.mobile_protocol import MobileProtocolError, validate_mobile_frame


def test_accepts_text_as_existing_listen_detect_semantics():
    raw = json.dumps({
        "type": "listen",
        "version": 1,
        "state": "detect",
        "text": "今天天气怎么样？",
        "message_id": "msg_01",
    })

    normalized = validate_mobile_frame(raw)

    assert normalized["type"] == "listen"
    assert normalized["state"] == "detect"
    assert normalized["text"] == "今天天气怎么样？"


@pytest.mark.parametrize("payload", [
    {"type": "listen", "version": 2, "state": "detect", "text": "hi", "message_id": "m"},
    {"type": "listen", "version": 1, "state": "detect", "text": "hi", "message_id": "m", "tts_text": "播报我"},
    {"type": "listen", "version": 1, "state": "detect", "text": "x" * 2001, "message_id": "m"},
    {"type": "listen", "version": 1, "state": "unknown"},
    {"type": "listen", "version": 1, "state": "detect", "text": "hi", "message_id": "中文"},
    {"type": "ping", "version": 1, "message_id": 7},
])
def test_rejects_unknown_version_fields_oversize_and_enum(payload):
    with pytest.raises(MobileProtocolError):
        validate_mobile_frame(json.dumps(payload))


def test_rejects_trailing_json_and_non_object():
    with pytest.raises(MobileProtocolError):
        validate_mobile_frame('{"type":"ping","version":1,"message_id":"m"}{}')
    with pytest.raises(MobileProtocolError):
        validate_mobile_frame("[]")
