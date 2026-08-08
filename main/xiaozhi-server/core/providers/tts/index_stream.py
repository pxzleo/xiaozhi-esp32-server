import os
import time
import queue
import aiohttp
import asyncio
import requests
import traceback
from config.logger import setup_logging
from core.utils.tts import MarkdownCleaner
from core.providers.tts.base import TTSProviderBase
from core.utils import opus_encoder_utils, textUtils
from core.providers.tts.dto.dto import SentenceType, ContentType, InterfaceType

TAG = __name__
logger = setup_logging()
INDEX_STREAM_REQUEST_TIMEOUT_SECONDS = 10


class TTSProvider(TTSProviderBase):
    def __init__(self, config, delete_audio_file):
        super().__init__(config, delete_audio_file)
        self.interface_type = InterfaceType.SINGLE_STREAM
        self.voice = config.get("voice", "xiao_he")
        if config.get("private_voice"):
            self.voice = config.get("private_voice")
        else:
            self.voice = config.get("voice", "xiao_he")
        self.api_url = config.get("api_url", "http://8.138.114.124:11996/tts")
        self.audio_format = "pcm"
        self.before_stop_play_files = []

        # 创建Opus编码器 需注意接口返回的采样率为24000
        self.opus_encoder = opus_encoder_utils.OpusEncoderUtils(
            sample_rate=24000, channels=1, frame_size_ms=60
        )

        # PCM缓冲区
        self.pcm_buffer = bytearray()

    def tts_text_priority_thread(self):
        """流式文本处理线程"""
        while not self.conn.stop_event.is_set():
            message = None
            try:
                message = self.tts_text_queue.get(timeout=1)
                if self.conn.client_abort or message.sentence_id != self.conn.sentence_id:
                    self._mark_sentence_completion_failed(message)
                    continue
                if message.sentence_type == SentenceType.FIRST:
                    self._reset_sentence_completion(message.sentence_id)
                    # 初始化参数
                    self.current_sentence_id = message.sentence_id
                    self.tts_stop_request = False
                    self.processed_chars = 0
                    self.tts_text_buff = []
                    self.before_stop_play_files.clear()
                elif ContentType.TEXT == message.content_type:
                    self.tts_text_buff.append(message.content_detail)
                    segment_text = self._get_segment_text()
                    if segment_text:
                        if not self.to_tts_single_stream(segment_text):
                            self._mark_sentence_completion_failed(message)

                elif ContentType.FILE == message.content_type:
                    # 先把待播报的引导语发出，为后续音乐建立有效的 TTS 会话。
                    full_text = "".join(self.tts_text_buff)
                    if full_text[self.processed_chars :]:
                        self._process_remaining_text_stream()
                    logger.bind(tag=TAG).info(
                        f"添加音频文件到待播放列表: {message.content_file}"
                    )
                    try:
                        if message.content_file and os.path.exists(message.content_file):
                            # 音乐文件逐帧转换后立即进入发送队列。
                            def enqueue_current_file_frame(audio_data):
                                if self.conn.stop_event.is_set():
                                    raise InterruptedError
                                if self.conn.client_abort:
                                    raise InterruptedError
                                if message.sentence_id != self.conn.sentence_id:
                                    raise InterruptedError
                                self.tts_audio_queue.put(
                                    (
                                        SentenceType.MIDDLE,
                                        audio_data,
                                        None,
                                        message.sentence_id,
                                    )
                                )

                            playback_callback = self._playback_file_callback(
                                message, enqueue_current_file_frame
                            )
                            self._process_audio_file_stream(
                                message.content_file,
                                callback=playback_callback,
                            )
                    except InterruptedError:
                        self._mark_sentence_completion_failed(message)
                    finally:
                        if message.completion_event:
                            self._forward_sentence_completion(message)
                            message.completion_event = None

                if message.sentence_type == SentenceType.LAST:
                    # 处理剩余的文本
                    if not self._process_remaining_text_stream(True):
                        self._mark_sentence_completion_failed(message)
                if message.completion_event:
                    self._forward_sentence_completion(message)

            except queue.Empty:
                continue
            except Exception as e:
                if message is not None:
                    self._mark_sentence_completion_failed(message)
                logger.bind(tag=TAG).error(
                    f"处理TTS文本失败: {str(e)}, 类型: {type(e).__name__}, 堆栈: {traceback.format_exc()}"
                )

    def _process_remaining_text_stream(self, is_last=False):
        """处理剩余的文本并生成语音
        Returns:
            bool: 是否成功处理了文本
        """
        full_text = "".join(self.tts_text_buff)
        remaining_text = full_text[self.processed_chars :]
        if remaining_text:
            segment_text = textUtils.get_string_no_punctuation_or_emoji(remaining_text)
            if segment_text:
                succeeded = self.to_tts_single_stream(segment_text, is_last)
                self.processed_chars += len(full_text)
                return succeeded
            else:
                self._process_before_stop_play_files()
        else:
            self._process_before_stop_play_files()
        return True

    def to_tts_single_stream(self, text, is_last=False):
        try:
            original_text = text
            text = MarkdownCleaner.clean_markdown(text)
            if self._correct_words_pattern:
                text = self._correct_words_pattern.sub(lambda m: self.correct_words[m.group(0)], text)
            try:
                asyncio.run(self.text_to_speak(text, is_last))
            except Exception as e:
                logger.bind(tag=TAG).warning(
                    f"语音生成失败: {original_text}，错误: {e}"
                )
                return False
            logger.bind(tag=TAG).info(f"语音生成成功: {original_text}")
            return True
        except Exception as e:
            logger.bind(tag=TAG).error(f"Failed to generate TTS file: {e}")
            return False

    async def text_to_speak(self, text, is_last):
        """流式处理TTS音频，每句只推送一次音频列表"""
        payload = {"text": text, "character": self.voice}

        frame_bytes = int(
            self.opus_encoder.sample_rate
            * self.opus_encoder.channels  # 1
            * self.opus_encoder.frame_size_ms
            / 1000
            * 2
        )  # 16-bit = 2 bytes
        try:
            async with aiohttp.ClientSession() as session:
                async with session.post(
                    self.api_url,
                    json=payload,
                    timeout=INDEX_STREAM_REQUEST_TIMEOUT_SECONDS,
                ) as resp:

                    if resp.status != 200:
                        raise RuntimeError(f"TTS请求失败: HTTP {resp.status}")

                    self.pcm_buffer.clear()
                    self.tts_audio_queue.put(
                        (
                            SentenceType.FIRST,
                            [],
                            text,
                            getattr(self, "current_sentence_id", None),
                        )
                    )

                    # 处理音频流数据
                    async for chunk in resp.content.iter_any():
                        data = chunk[0] if isinstance(chunk, (list, tuple)) else chunk
                        if not data:
                            continue

                        self.pcm_buffer.extend(data)

                        while len(self.pcm_buffer) >= frame_bytes:
                            frame = bytes(self.pcm_buffer[:frame_bytes])
                            del self.pcm_buffer[:frame_bytes]

                            self.opus_encoder.encode_pcm_to_opus_stream(
                                frame,
                                end_of_stream=False,
                                callback=self.handle_opus
                            )

                    # flush 剩余不足一帧的数据
                    if self.pcm_buffer:
                        self.opus_encoder.encode_pcm_to_opus_stream(
                            bytes(self.pcm_buffer),
                            end_of_stream=True,
                            callback=self.handle_opus
                        )
                        self.pcm_buffer.clear()

                    # 如果是最后一段，输出音频获取完毕
                    if is_last:
                        self._process_before_stop_play_files()

        except Exception as e:
            logger.bind(tag=TAG).error(f"TTS请求异常: {e}")
            self._enqueue_tts_error_end()
            raise

    def _enqueue_tts_error_end(self):
        sentence_id = getattr(self, "current_sentence_id", None)
        owns_audio_session = (
            getattr(self.conn, "server_audio_playback_sentence_id", None)
            == sentence_id
        )
        if not owns_audio_session:
            self.tts_audio_queue.put(
                (
                    SentenceType.LAST,
                    [],
                    None,
                    sentence_id,
                )
            )

    def audio_to_pcm_data_stream(
        self, audio_file_path, callback=None
    ):
        """音频文件转换为PCM编码，使用24kHz采样率"""
        from core.utils.util import audio_to_data_stream
        return audio_to_data_stream(audio_file_path, is_opus=False, callback=callback, sample_rate=24000, opus_encoder=None)

    def audio_to_opus_data_stream(
        self, audio_file_path, callback=None
    ):
        """音频文件转换为Opus编码，使用24kHz采样率和自己的编码器"""
        from core.utils.util import audio_to_data_stream
        return audio_to_data_stream(audio_file_path, is_opus=True, callback=callback, sample_rate=24000, opus_encoder=self.opus_encoder)

    async def close(self):
        """资源清理"""
        await super().close()
        if hasattr(self, "opus_encoder"):
            self.opus_encoder.close()

    def to_tts(self, text: str) -> list:
        """非流式TTS处理，用于测试及保存音频文件的场景
        Args:
            text: 要转换的文本
        Returns:
            list: 返回opus编码后的音频数据列表
        """
        start_time = time.time()
        text = MarkdownCleaner.clean_markdown(text)
        if self._correct_words_pattern:
            text = self._correct_words_pattern.sub(lambda m: self.correct_words[m.group(0)], text)

        payload = {"text": text, "character": self.voice}

        try:
            with requests.post(self.api_url, json=payload, timeout=5) as response:
                if response.status_code != 200:
                    logger.bind(tag=TAG).error(
                        f"TTS请求失败: {response.status_code}, {response.text}"
                    )
                    return []

                logger.info(f"TTS请求成功: {text}, 耗时: {time.time() - start_time}秒")

                # 使用opus编码器处理PCM数据
                opus_datas = []
                pcm_data = response.content

                # 计算每帧的字节数
                frame_bytes = int(
                    self.opus_encoder.sample_rate
                    * self.opus_encoder.channels
                    * self.opus_encoder.frame_size_ms
                    / 1000
                    * 2
                )

                # 分帧处理PCM数据
                for i in range(0, len(pcm_data), frame_bytes):
                    frame = pcm_data[i : i + frame_bytes]
                    if len(frame) < frame_bytes:
                        # 最后一帧可能不足，用0填充
                        frame = frame + b"\x00" * (frame_bytes - len(frame))

                    self.opus_encoder.encode_pcm_to_opus_stream(
                        frame,
                        end_of_stream=(i + frame_bytes >= len(pcm_data)),
                        callback=lambda opus: opus_datas.append(opus)
                    )

                return opus_datas

        except Exception as e:
            logger.bind(tag=TAG).error(f"TTS请求异常: {e}")
            return []
