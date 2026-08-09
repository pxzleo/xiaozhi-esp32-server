import os
import re
import uuid
import time
import asyncio
import edge_tts
from datetime import datetime
from config.logger import setup_logging
from core.utils import textUtils
from core.utils.tts import MarkdownCleaner, sanitize_spoken_text
from core.providers.tts.base import TTSProviderBase
from core.providers.tts.dto.dto import SentenceType


TAG = __name__
logger = setup_logging()


class TTSProvider(TTSProviderBase):
    MIN_FIRST_SEGMENT_CHARS = 6
    DISCARDABLE_TRAILING_FILLERS = frozenset(
        {"哈哈", "嘿嘿", "哎呀", "哦", "嗯", "好呀"}
    )
    PARTIAL_STREAM_FAILURE_NOTICE = "语音连接中断，请再问一次"

    TTS_PARAM_CONFIG = [
        ("ttsVolume", "volume", 0, 100, 50, int),
        ("ttsRate", "speech_rate", -100, 100, 0, int),
        ("ttsPitch", "pitch_rate", -100, 100, 0, int),
    ]

    def __init__(self, config, delete_audio_file):
        super().__init__(config, delete_audio_file)
        if config.get("private_voice"):
            self.voice = config.get("private_voice")
        else:
            self.voice = config.get("voice")
        self.audio_file_type = config.get("format", "mp3")

        volume = config.get("volume", "50")
        self.volume = int(volume) if volume else 50

        speech_rate = config.get("rate", "0")
        self.speech_rate = int(speech_rate) if speech_rate else 0

        pitch_rate = config.get("pitch", "0")
        self.pitch_rate = int(pitch_rate) if pitch_rate else 0

        # 应用百分比调整
        self._apply_percentage_params(config)

        self.edge_rate = f"{self.speech_rate:+}%"
        self.edge_volume = f"{self.volume:+}%"
        self.edge_pitch = f"{self.pitch_rate:+}Hz"

    def generate_filename(self, extension=".mp3"):
        return os.path.join(
            self.output_file,
            f"tts-{datetime.now().date()}@{uuid.uuid4().hex}{extension}",
        )

    def _stream_aborted(self):
        return (
            self.conn.client_abort
            or self.current_sentence_id != self.conn.sentence_id
            or self.conn.stop_event.is_set()
        )

    @classmethod
    def _sanitize_spoken_text(cls, text):
        """确定性移除模型偶尔输出的句首填充词，不依赖提示词遵循。"""
        return sanitize_spoken_text(text)

    def _notify_partial_stream_failure(self, original_text, audio_handler):
        if original_text == self.PARTIAL_STREAM_FAILURE_NOTICE:
            return
        self.to_tts_stream(
            self.PARTIAL_STREAM_FAILURE_NOTICE,
            opus_handler=audio_handler,
        )

    def _get_segment_text(self):
        """首段尽快播放，后续文字等本轮结束后合并成一次请求。"""
        full_text = "".join(self.tts_text_buff)
        current_text = full_text[self.processed_chars :]
        if not current_text:
            return None

        # Edge 每建立一次请求就会额外等待约 2~5 秒。首段已经
        # 开始播放后，不再按每句重复请求，由 LAST 统一合成剩余内容。
        if not self.is_first_sentence:
            return None

        punctuations = self.first_sentence_punctuations
        # 与公共实现保持一致：每种标点只取最后一次出现的位置，避免同一批
        # 到达的多个完整句子被拆成多次 Edge 请求。
        punctuation_positions = sorted(
            {
                position
                for punctuation in punctuations
                if (position := current_text.rfind(punctuation)) != -1
            }
        )

        for position in punctuation_positions:
            segment_text_raw = current_text[: position + 1]
            segment_text = textUtils.get_string_no_punctuation_or_emoji(
                segment_text_raw
            )
            if (
                self.is_first_sentence
                and len(segment_text) < self.MIN_FIRST_SEGMENT_CHARS
            ):
                continue

            self.processed_chars += len(segment_text_raw)
            self.is_first_sentence = False
            return segment_text

        return None

    def _process_remaining_text_stream(self, opus_handler=None):
        full_text = "".join(self.tts_text_buff)
        remaining_text = full_text[self.processed_chars :]
        cleaned_text = textUtils.get_string_no_punctuation_or_emoji(remaining_text)
        normalized_text = re.sub(
            r"[^0-9A-Za-z_\u4e00-\u9fff]", "", cleaned_text
        ).strip()
        if normalized_text in self.DISCARDABLE_TRAILING_FILLERS:
            self.processed_chars = len(full_text)
            logger.bind(tag=TAG).info(
                f"跳过无意义的尾部短语，避免额外EdgeTTS请求: {normalized_text}"
            )
            return False
        return super()._process_remaining_text_stream(opus_handler=opus_handler)

    def to_tts_stream(self, text, opus_handler=None):
        """边接收 Edge MP3，边解码并向设备发送音频帧。"""
        original_text = self._sanitize_spoken_text(text)
        text = MarkdownCleaner.clean_markdown(original_text)
        if self._correct_words_pattern:
            text = self._correct_words_pattern.sub(
                lambda match: self.correct_words[match.group(0)], text
            )

        if not text or self._stream_aborted():
            return None

        audio_handler = opus_handler or self.handle_opus
        max_attempts = 2

        for attempt in range(1, max_attempts + 1):
            first_marker_sent = False
            audio_packet_count = 0
            start_time = time.monotonic()

            def send_first_marker():
                nonlocal first_marker_sent
                if first_marker_sent or self._stream_aborted():
                    return
                self.tts_audio_queue.put(
                    (
                        SentenceType.FIRST,
                        None,
                        original_text,
                        self.current_sentence_id,
                    )
                )
                first_marker_sent = True
                logger.bind(tag=TAG).info(
                    f"EdgeTTS首个音频包耗时: {time.monotonic() - start_time:.3f}s"
                )

            def emit_audio(audio_data):
                nonlocal audio_packet_count
                if not audio_data or self._stream_aborted():
                    return
                send_first_marker()
                audio_handler(audio_data)
                audio_packet_count += 1

            try:
                completed = asyncio.run(
                    self._stream_text_to_opus(text, emit_audio, send_first_marker)
                )
                if completed and audio_packet_count > 0:
                    logger.bind(tag=TAG).info(
                        f"EdgeTTS流式生成成功: {original_text}，"
                        f"音频包{audio_packet_count}个，总耗时{time.monotonic() - start_time:.3f}s"
                    )
                return None
            except Exception as error:
                if audio_packet_count > 0 or attempt == max_attempts:
                    logger.bind(tag=TAG).error(
                        f"EdgeTTS流式生成失败: {original_text}，错误: {error}"
                    )
                    if audio_packet_count > 0:
                        self._notify_partial_stream_failure(
                            original_text, audio_handler
                        )
                    return None
                logger.bind(tag=TAG).warning(
                    f"EdgeTTS流式生成失败，准备重试（{attempt}/{max_attempts}）: {error}"
                )

        return None

    async def _stream_text_to_opus(self, text, audio_handler, first_audio_handler):
        """将 Edge 的 MP3 数据增量解码为设备所需的 PCM/Opus 数据。"""
        if self._stream_aborted():
            return False

        process = await asyncio.create_subprocess_exec(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "mp3",
            "-i",
            "pipe:0",
            "-vn",
            "-ac",
            "1",
            "-ar",
            str(self.conn.sample_rate),
            "-f",
            "s16le",
            "pipe:1",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stderr_task = asyncio.create_task(process.stderr.read())
        pcm_buffer = bytearray()
        frame_bytes = int(self.conn.sample_rate * 0.06 * 2)

        def emit_audio_data(audio_data):
            if not audio_data or self._stream_aborted():
                return
            first_audio_handler()
            audio_handler(audio_data)

        def emit_pcm(pcm_data, end_of_stream=False):
            if self._stream_aborted():
                return
            if getattr(self.conn, "audio_format", "opus") == "pcm":
                if pcm_data:
                    emit_audio_data(pcm_data)
                return
            self.opus_encoder.encode_pcm_to_opus_stream(
                pcm_data,
                end_of_stream=end_of_stream,
                callback=emit_audio_data,
            )

        async def read_decoded_audio():
            while True:
                pcm_chunk = await process.stdout.read(8192)
                if not pcm_chunk:
                    break
                if self._stream_aborted():
                    return
                pcm_buffer.extend(pcm_chunk)
                while len(pcm_buffer) >= frame_bytes:
                    frame = bytes(pcm_buffer[:frame_bytes])
                    del pcm_buffer[:frame_bytes]
                    emit_pcm(frame)

        reader_task = asyncio.create_task(read_decoded_audio())
        communicate = edge_tts.Communicate(
            text,
            voice=self.voice,
            rate=self.edge_rate,
            volume=self.edge_volume,
            pitch=self.edge_pitch,
        )

        stream_error = None
        try:
            async for chunk in communicate.stream():
                if self._stream_aborted():
                    process.terminate()
                    break
                if chunk["type"] != "audio" or not chunk["data"]:
                    continue
                process.stdin.write(chunk["data"])
                await process.stdin.drain()
        except (BrokenPipeError, ConnectionResetError):
            # FFmpeg失败时由下面的退出码和stderr给出根因。
            pass
        except Exception as error:
            stream_error = error
        finally:
            if process.stdin and not process.stdin.is_closing():
                process.stdin.close()
                try:
                    await process.stdin.wait_closed()
                except (BrokenPipeError, ConnectionResetError):
                    pass

        await reader_task
        return_code = await process.wait()
        stderr = (await stderr_task).decode("utf-8", errors="replace").strip()

        if self._stream_aborted():
            return False
        if return_code != 0:
            raise RuntimeError(stderr or f"FFmpeg退出码: {return_code}")
        if stream_error is not None:
            raise stream_error

        if pcm_buffer:
            if getattr(self.conn, "audio_format", "opus") == "pcm":
                pcm_buffer.extend(b"\x00" * (frame_bytes - len(pcm_buffer)))
                emit_pcm(bytes(pcm_buffer))
            else:
                emit_pcm(bytes(pcm_buffer), end_of_stream=True)
            pcm_buffer.clear()
        elif getattr(self.conn, "audio_format", "opus") != "pcm":
            emit_pcm(b"", end_of_stream=True)

        return True

    async def text_to_speak(self, text, output_file):
        try:
            communicate = edge_tts.Communicate(
                text,
                voice=self.voice,
                rate=self.edge_rate,
                volume=self.edge_volume,
                pitch=self.edge_pitch,
            )
            if output_file:
                # 确保目录存在并创建空文件
                os.makedirs(os.path.dirname(output_file), exist_ok=True)
                with open(output_file, "wb") as f:
                    pass

                # 流式写入音频数据
                with open(output_file, "ab") as f:  # 改为追加模式避免覆盖
                    async for chunk in communicate.stream():
                        if chunk["type"] == "audio":  # 只处理音频数据块
                            f.write(chunk["data"])
            else:
                # 返回音频二进制数据
                audio_bytes = b""
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        audio_bytes += chunk["data"]
                return audio_bytes
        except Exception as e:
            error_msg = f"Edge TTS请求失败: {e}"
            raise Exception(error_msg)  # 抛出异常，让调用方捕获
