import time
import json
import uuid
import asyncio
import re
from difflib import SequenceMatcher
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from core.connection import ConnectionHandler
from core.utils.util import audio_to_data
from core.handle.abortHandle import handleAbortMessage, cancelActiveLLMResponse
from core.handle.intentHandler import handle_user_intent
from core.utils.output_counter import check_device_output_limit
from core.handle.sendAudioHandle import send_stt_message, SentenceType

TAG = __name__
TTS_ECHO_HISTORY_TTL_SECONDS = 15.0
TTS_ECHO_SIMILARITY_THRESHOLD = 0.72


def _normalize_echo_text(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z_\u4e00-\u9fff]", "", value).lower()


def is_likely_tts_echo(conn: "ConnectionHandler", text: str) -> bool:
    """仅过滤正在播报内容的高相似回声，保留用户主动插话。"""
    if not conn.client_is_speaking:
        return False

    normalized_input = _normalize_echo_text(text)
    if len(normalized_input) < 4:
        return False

    now = time.monotonic()
    history = getattr(conn, "recent_tts_texts", None)
    if history is None:
        last_tts_text = getattr(conn, "last_tts_text", "")
        last_tts_text_at = getattr(conn, "last_tts_text_at", None)
        recent_tts_texts = [
            last_tts_text
        ] if last_tts_text and (
            last_tts_text_at is None
            or now - last_tts_text_at <= TTS_ECHO_HISTORY_TTL_SECONDS
        ) else []
    else:
        recent_tts_texts = [
            tts_text
            for recorded_at, tts_text in history
            if now - recorded_at <= TTS_ECHO_HISTORY_TTL_SECONDS
        ]

    for tts_text in recent_tts_texts:
        normalized_tts = _normalize_echo_text(tts_text)
        if len(normalized_tts) < 4:
            continue
        if (
            SequenceMatcher(None, normalized_input, normalized_tts).ratio()
            >= TTS_ECHO_SIMILARITY_THRESHOLD
        ):
            return True
    return False


async def handleAudioMessage(conn: "ConnectionHandler", pcm_frame):
    # 当前片段是否有人说话
    have_voice = conn.vad.is_vad(conn, pcm_frame)
    # 如果设备刚刚被唤醒，短暂忽略VAD检测
    if hasattr(conn, "just_woken_up") and conn.just_woken_up:
        have_voice = False
        # 设置一个短暂延迟后恢复VAD检测
        if not hasattr(conn, "vad_resume_task") or conn.vad_resume_task.done():
            conn.vad_resume_task = asyncio.create_task(resume_vad_detection(conn))
        return
    # 服务端AEC功能需要实时触发打断
    if conn.client_aec and have_voice:
        if conn.client_is_speaking and conn.client_listen_mode != "manual":
            await handleAbortMessage(conn)
    # 设备长时间空闲检测，用于say goodbye
    await no_voice_close_connect(conn, have_voice)
    # 接收音频
    await conn.asr.receive_audio(conn, pcm_frame, have_voice)


async def resume_vad_detection(conn: "ConnectionHandler"):
    # 等待2秒后恢复VAD检测
    await asyncio.sleep(2)
    conn.just_woken_up = False


async def startToChat(conn: "ConnectionHandler", text):
    # 检查输入是否是JSON格式（包含说话人信息）
    speaker_name = None
    actual_text = text
    actual_content = None
    echo_text = text

    try:
        # 尝试解析JSON格式的输入
        if text.strip().startswith("{") and text.strip().endswith("}"):
            data = json.loads(text)
            if "content" in data:
                echo_text = data["content"]
            if "speaker" in data and "content" in data:
                speaker_name = data["speaker"]
                actual_content = data["content"]
                conn.logger.bind(tag=TAG).info(f"解析到说话人信息: {speaker_name}")
    except (json.JSONDecodeError, KeyError):
        # 如果解析失败，继续使用原始文本
        pass

    if conn.need_bind:
        await check_bind_device(conn)
        return

    if is_likely_tts_echo(conn, echo_text):
        conn.logger.bind(tag=TAG).info(
            f"忽略与当前播报高度相似的ASR回声: {echo_text}"
        )
        return

    previous_sentence_id = conn.sentence_id
    if conn.client_is_speaking and conn.client_listen_mode != "manual":
        await handleAbortMessage(conn)
    else:
        conn.abort_generation = getattr(conn, "abort_generation", 0) + 1
        conn.client_abort = True
        await cancelActiveLLMResponse(conn, previous_sentence_id)
    current_sentence_id = uuid.uuid4().hex
    conn.sentence_id = current_sentence_id
    conn.client_abort = False

    # 仅在该说话人首次出现时保留 {"speaker":...} JSON，让模型自然称呼一次；
    # 后续轮降为纯文本，避免每轮重复出现名字诱导模型反复称呼。
    if speaker_name:
        if speaker_name not in conn.introduced_speakers:
            conn.introduced_speakers.add(speaker_name)
        else:
            actual_text = actual_content
        conn.current_speaker = speaker_name
    else:
        conn.current_speaker = None

    # 如果当日的输出字数大于限定的字数
    if conn.max_output_size > 0:
        if check_device_output_limit(
            conn.headers.get("device-id"), conn.max_output_size
        ):
            await max_out_size(conn)
            return

    # 首先进行意图分析，使用实际文本内容
    intent_handled = await handle_user_intent(conn, actual_text)

    if intent_handled:
        # 如果意图已被处理，不再进行聊天
        return

    # 意图未被处理，继续常规聊天流程，使用实际文本内容
    await send_stt_message(conn, actual_text)

    # 在提交线程任务前分配轮次ID。这样即使旧任务稍后恢复执行，也能通过
    # sentence_id 不匹配识别自己已经失效，不能串入新轮次。
    conn.executor.submit(conn.chat, actual_text, 0, current_sentence_id)


async def no_voice_close_connect(conn: "ConnectionHandler", have_voice):
    if have_voice:
        conn.last_activity_time = time.time() * 1000
        return
    # 只有在已经初始化过时间戳的情况下才进行超时检查
    if conn.last_activity_time > 0.0:
        no_voice_time = time.time() * 1000 - conn.last_activity_time
        close_connection_no_voice_time = int(
            conn.config.get("close_connection_no_voice_time", 120)
        )
        if (
            not conn.close_after_chat
            and no_voice_time > 1000 * close_connection_no_voice_time
        ):
            conn.close_after_chat = True
            conn.client_abort = False
            end_prompt = conn.config.get("end_prompt", {})
            if end_prompt and end_prompt.get("enable", True) is False:
                conn.logger.bind(tag=TAG).info("结束对话，无需发送结束提示语")
                await conn.close()
                return
            prompt = end_prompt.get("prompt")
            if not prompt:
                prompt = "请你以```时间过得真快```未来头，用富有感情、依依不舍的话来结束这场对话吧。！"
            await startToChat(conn, prompt)


async def max_out_size(conn: "ConnectionHandler"):
    # 播放超出最大输出字数的提示
    conn.client_abort = False
    text = "不好意思，我现在有点事情要忙，明天这个时候我们再聊，约好了哦！明天不见不散，拜拜！"
    await send_stt_message(conn, text)
    file_path = "config/assets/max_output_size.wav"
    opus_packets = await audio_to_data(file_path)
    conn.tts.tts_audio_queue.put((SentenceType.LAST, opus_packets, text))
    conn.close_after_chat = True


async def check_bind_device(conn: "ConnectionHandler"):
    if conn.bind_code:
        # 确保bind_code是6位数字
        if len(conn.bind_code) != 6:
            conn.logger.bind(tag=TAG).error(f"无效的绑定码格式: {conn.bind_code}")
            text = "绑定码格式错误，请检查配置。"
            await send_stt_message(conn, text)
            return

        text = f"请登录控制面板，输入{conn.bind_code}，绑定设备。"
        await send_stt_message(conn, text)

        # 播放提示音
        music_path = "config/assets/bind_code.wav"
        opus_packets = await audio_to_data(music_path)
        conn.tts.tts_audio_queue.put((SentenceType.FIRST, opus_packets, text))

        # 逐个播放数字
        for i in range(6):  # 确保只播放6位数字
            try:
                digit = conn.bind_code[i]
                num_path = f"config/assets/bind_code/{digit}.wav"
                num_packets = await audio_to_data(num_path)
                conn.tts.tts_audio_queue.put((SentenceType.MIDDLE, num_packets, None))
            except Exception as e:
                conn.logger.bind(tag=TAG).error(f"播放数字音频失败: {e}")
                continue
        conn.tts.tts_audio_queue.put((SentenceType.LAST, [], None))
    else:
        # 播放未绑定提示
        conn.client_abort = False
        text = f"没有找到该设备的版本信息，请正确配置 OTA地址，然后重新编译固件。"
        await send_stt_message(conn, text)
        music_path = "config/assets/bind_not_found.wav"
        opus_packets = await audio_to_data(music_path)
        conn.tts.tts_audio_queue.put((SentenceType.LAST, opus_packets, text))
