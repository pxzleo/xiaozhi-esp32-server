import json
import re
from dataclasses import dataclass


MAX_TEXT_FRAME_BYTES = 8192
MAX_AUDIO_FRAME_BYTES = 16384
MAX_USER_TEXT_CHARS = 2000
PROTOCOL_VERSION = 1
MESSAGE_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
ALLOWED_CAPABILITIES = frozenset(
    {"text_chat", "voice_session", "notification_gateway"}
)


@dataclass
class MobileProtocolError(ValueError):
    code: str
    message: str

    def __str__(self):
        return self.message


def parse_capabilities(value: str) -> list[str]:
    if not isinstance(value, str) or not value:
        raise MobileProtocolError("INVALID_CAPABILITIES", "缺少手机能力集合")
    capabilities = value.split(",")
    if (
        len(capabilities) > 8
        or len(capabilities) != len(set(capabilities))
        or not set(capabilities).issubset(ALLOWED_CAPABILITIES)
    ):
        raise MobileProtocolError("INVALID_CAPABILITIES", "手机能力集合无效")
    return capabilities


def validate_mobile_frame(message: str) -> dict:
    if not isinstance(message, str):
        raise MobileProtocolError("INVALID_FRAME", "手机控制帧必须是文本 JSON")
    if len(message.encode("utf-8")) > MAX_TEXT_FRAME_BYTES:
        raise MobileProtocolError("FRAME_TOO_LARGE", "手机控制帧超过大小限制")
    try:
        decoder = json.JSONDecoder()
        payload, end = decoder.raw_decode(message)
        if message[end:].strip():
            raise MobileProtocolError("INVALID_JSON", "手机控制帧包含尾随 JSON")
    except MobileProtocolError:
        raise
    except (json.JSONDecodeError, UnicodeError) as exception:
        raise MobileProtocolError("INVALID_JSON", "手机控制帧不是有效 JSON") from exception
    if not isinstance(payload, dict):
        raise MobileProtocolError("INVALID_FRAME", "手机控制帧必须是 JSON 对象")
    if payload.get("version") != PROTOCOL_VERSION:
        raise MobileProtocolError("UNSUPPORTED_VERSION", "不支持的手机协议版本")

    frame_type = payload.get("type")
    validators = {
        "hello": _validate_hello,
        "listen": _validate_listen,
        "abort": _validate_abort,
        "ping": _validate_ping,
    }
    validator = validators.get(frame_type)
    if validator is None:
        raise MobileProtocolError("UNKNOWN_TYPE", "未知的手机控制帧类型")
    validator(payload)
    return payload


def _require_exact_fields(payload: dict, required: set[str], optional: set[str]):
    missing = required.difference(payload)
    unknown = set(payload).difference(required | optional)
    if missing:
        raise MobileProtocolError("MISSING_FIELD", "手机控制帧缺少必填字段")
    if unknown:
        raise MobileProtocolError("UNKNOWN_FIELD", "手机控制帧包含未知字段")


def _validate_hello(payload: dict):
    _require_exact_fields(
        payload,
        {"type", "version", "transport", "audio_params", "features"},
        {"message_id"},
    )
    if payload["transport"] != "websocket":
        raise MobileProtocolError("INVALID_TRANSPORT", "手机传输类型无效")
    audio = payload["audio_params"]
    if not isinstance(audio, dict) or set(audio) != {"format", "sample_rate", "channels", "frame_duration"}:
        raise MobileProtocolError("INVALID_AUDIO_PARAMS", "手机音频参数字段无效")
    if audio != {"format": "opus", "sample_rate": 24000, "channels": 1, "frame_duration": 60}:
        raise MobileProtocolError("UNSUPPORTED_AUDIO_PARAMS", "手机音频参数不受支持")
    features = payload["features"]
    if not isinstance(features, dict) or set(features).difference({"aec"}):
        raise MobileProtocolError("INVALID_FEATURES", "手机特性字段无效")
    if "aec" in features and not isinstance(features["aec"], bool):
        raise MobileProtocolError("INVALID_FEATURES", "aec 必须是布尔值")
    _validate_optional_message_id(payload)


def _validate_listen(payload: dict):
    _require_exact_fields(
        payload, {"type", "version", "state"}, {"mode", "text", "message_id"}
    )
    state = payload["state"]
    if state not in {"start", "stop", "detect"}:
        raise MobileProtocolError("INVALID_STATE", "手机拾音状态无效")
    if "mode" in payload and payload["mode"] not in {"auto", "manual", "realtime"}:
        raise MobileProtocolError("INVALID_MODE", "手机拾音模式无效")
    if state == "detect":
        text = payload.get("text")
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_USER_TEXT_CHARS:
            raise MobileProtocolError("INVALID_TEXT", "手机文字消息为空或超过长度限制")
        message_id = payload.get("message_id")
        if not isinstance(message_id, str) or not MESSAGE_ID_PATTERN.fullmatch(message_id):
            raise MobileProtocolError("INVALID_MESSAGE_ID", "手机文字消息 ID 无效")
    elif "text" in payload:
        raise MobileProtocolError("UNEXPECTED_TEXT", "该拾音状态不接受文字")


def _validate_abort(payload: dict):
    _require_exact_fields(payload, {"type", "version"}, {"reason", "message_id"})
    if "reason" in payload and (
        not isinstance(payload["reason"], str) or len(payload["reason"]) > 64
    ):
        raise MobileProtocolError("INVALID_REASON", "打断原因无效")
    _validate_optional_message_id(payload)


def _validate_ping(payload: dict):
    _require_exact_fields(payload, {"type", "version"}, {"message_id"})
    _validate_optional_message_id(payload)


def _validate_optional_message_id(payload: dict):
    if "message_id" in payload and (
        not isinstance(payload["message_id"], str)
        or not MESSAGE_ID_PATTERN.fullmatch(payload["message_id"])
    ):
        raise MobileProtocolError("INVALID_MESSAGE_ID", "手机消息 ID 无效")
