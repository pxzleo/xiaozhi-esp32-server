import json
import asyncio
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
TAG = __name__
LLM_CANCEL_TIMEOUT_SECONDS = 1.0
_CURRENT_LLM_REQUEST = object()


async def cancelActiveLLMResponse(
    conn: "ConnectionHandler", request_id=_CURRENT_LLM_REQUEST
):
    cancel_response = getattr(getattr(conn, "llm", None), "cancel_response", None)
    if not callable(cancel_response):
        return False
    active_request_id = (
        conn.sentence_id
        if request_id is _CURRENT_LLM_REQUEST
        else request_id
    )
    try:
        return await asyncio.wait_for(
            asyncio.to_thread(
                cancel_response,
                conn.session_id,
                active_request_id,
            ),
            timeout=LLM_CANCEL_TIMEOUT_SECONDS,
        )
    except asyncio.TimeoutError:
        conn.logger.bind(tag=TAG).error(
            f"取消LLM轮次超时: session={conn.session_id}, request={active_request_id}"
        )
    except Exception as error:
        conn.logger.bind(tag=TAG).error(
            f"取消LLM轮次失败: session={conn.session_id}, request={active_request_id}, error={error}"
        )
    return False


async def handleAbortMessage(conn: "ConnectionHandler"):
    conn.logger.bind(tag=TAG).info("Abort message received")
    # 设置成打断状态，会自动打断llm、tts任务
    conn.close_after_chat = False
    conn.client_abort = True
    await cancelActiveLLMResponse(conn)
    conn.clear_queues()
    # 打断客户端说话状态
    await conn.websocket.send(
        json.dumps({"type": "tts", "state": "stop", "session_id": conn.session_id})
    )
    conn.clearSpeakStatus()
    conn.logger.bind(tag=TAG).info("Abort message received-end")
