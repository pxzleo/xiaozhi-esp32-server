import threading
import time

import numpy as np

from config.logger import setup_logging
from core.providers.firered_utils import (
    import_firered_module,
    parse_bool,
    parse_float,
    parse_int,
    require_model_dir,
    require_model_files,
)
from core.providers.vad.base import VADProviderBase

TAG = __name__
logger = setup_logging()

_SAMPLE_WIDTH_BYTES = 2
_FRAME_LENGTH_SAMPLES = 400  # 官方 FireRedVAD: 25 ms @ 16 kHz
_FRAME_SHIFT_SAMPLES = 160  # 官方 FireRedVAD: 10 ms @ 16 kHz
_DIAGNOSTIC_INTERVAL_SECONDS = 5.0


class VADProvider(VADProviderBase):
    def __init__(self, config):
        source_dir = config.get("source_dir", "")
        model_dir = require_model_dir(config.get("model_dir"), "FireRedVAD")
        require_model_files(
            model_dir, "FireRedVAD", ("cmvn.ark", "model.pth.tar")
        )
        self.use_gpu = parse_bool(config.get("use_gpu", False), "use_gpu")
        self.speech_threshold = parse_float(
            config.get("speech_threshold", 0.5), "speech_threshold", 0.0, 1.0
        )
        self.smooth_window_size = parse_int(
            config.get("smooth_window_size", 5), "smooth_window_size", 1, 100
        )
        self.pad_start_frame = parse_int(
            config.get("pad_start_frame", 5), "pad_start_frame", 1, 100
        )
        self.min_speech_frame = parse_int(
            config.get("min_speech_frame", 8), "min_speech_frame", 1, 1000
        )
        self.max_speech_frame = parse_int(
            config.get("max_speech_frame", 2000),
            "max_speech_frame",
            self.min_speech_frame,
            30000,
        )
        min_silence_ms = parse_int(
            config.get("min_silence_duration_ms", 700),
            "min_silence_duration_ms",
            50,
            6000,
        )
        self.min_silence_frame = max(1, (min_silence_ms + 9) // 10)

        firered_vad = import_firered_module(source_dir, "fireredvad")
        if self.use_gpu:
            import torch

            if not torch.cuda.is_available():
                raise RuntimeError("FireRedVAD 配置了 use_gpu，但 CUDA 不可用")

        model_config = firered_vad.FireRedStreamVadConfig(
            use_gpu=self.use_gpu,
            smooth_window_size=self.smooth_window_size,
            speech_threshold=self.speech_threshold,
            pad_start_frame=self.pad_start_frame,
            min_speech_frame=self.min_speech_frame,
            max_speech_frame=self.max_speech_frame,
            min_silence_frame=self.min_silence_frame,
        )
        loaded = firered_vad.FireRedStreamVad.from_pretrained(
            model_dir, model_config
        )
        self._audio_feat = loaded.audio_feat
        self._vad_model = loaded.vad_model
        self._postprocessor_type = type(loaded.postprocessor)
        self._inference_lock = threading.Lock()
        logger.bind(tag=TAG).info(
            f"FireRedVAD 初始化完成，device={'cuda' if self.use_gpu else 'cpu'}，"
            f"speech_threshold={self.speech_threshold}，"
            f"min_speech_duration_ms={self.min_speech_frame * 10}，"
            f"min_silence_duration_ms={min_silence_ms}"
        )

    def _new_postprocessor(self):
        return self._postprocessor_type(
            self.smooth_window_size,
            self.speech_threshold,
            self.pad_start_frame,
            self.min_speech_frame,
            self.max_speech_frame,
            self.min_silence_frame,
        )

    def _init_connection_state(self, conn):
        if not hasattr(conn, "_firered_vad_buffer"):
            conn._firered_vad_buffer = bytearray()
        if not hasattr(conn, "_firered_vad_model_caches"):
            conn._firered_vad_model_caches = None
        if not hasattr(conn, "_firered_vad_postprocessor"):
            conn._firered_vad_postprocessor = self._new_postprocessor()
        if not hasattr(conn, "_firered_vad_in_speech"):
            conn._firered_vad_in_speech = False
        if not hasattr(conn, "_firered_vad_diagnostic_started_at"):
            conn._firered_vad_diagnostic_started_at = time.monotonic()
            conn._firered_vad_diagnostic_frames = 0
            conn._firered_vad_diagnostic_max_prob = 0.0
            conn._firered_vad_diagnostic_max_rms = 0.0
            conn._firered_vad_diagnostic_logged = False

    def reset_conn_state(self, conn):
        self.release_conn_resources(conn)

    def release_conn_resources(self, conn):
        for attr in (
            "_firered_vad_buffer",
            "_firered_vad_model_caches",
            "_firered_vad_postprocessor",
            "_firered_vad_in_speech",
            "_firered_vad_diagnostic_started_at",
            "_firered_vad_diagnostic_frames",
            "_firered_vad_diagnostic_max_prob",
            "_firered_vad_diagnostic_max_rms",
            "_firered_vad_diagnostic_logged",
        ):
            if hasattr(conn, attr):
                delattr(conn, attr)

    def _detect_frame(self, conn, audio_frame):
        import torch

        feat, _ = self._audio_feat.extract(audio_frame)
        if feat.size(0) != 1:
            raise RuntimeError(
                f"FireRedVAD 特征帧数量异常: expected=1 actual={feat.size(0)}"
            )
        if self.use_gpu:
            feat = feat.cuda()
        with self._inference_lock, torch.no_grad():
            prob, caches = self._vad_model.forward(
                feat.unsqueeze(0), caches=conn._firered_vad_model_caches
            )
        conn._firered_vad_model_caches = caches
        raw_prob = prob.cpu().squeeze().item()
        return conn._firered_vad_postprocessor.process_one_frame(float(raw_prob))

    def is_vad(self, conn, pcm_frame):
        if conn.client_listen_mode == "manual":
            return True
        if not isinstance(pcm_frame, (bytes, bytearray)):
            raise TypeError("FireRedVAD 只接受 16kHz 16-bit 单声道 PCM 字节")

        self._init_connection_state(conn)
        conn._firered_vad_buffer.extend(pcm_frame)
        frame_bytes = _FRAME_LENGTH_SAMPLES * _SAMPLE_WIDTH_BYTES
        shift_bytes = _FRAME_SHIFT_SAMPLES * _SAMPLE_WIDTH_BYTES
        current_have_voice = False

        while len(conn._firered_vad_buffer) >= frame_bytes:
            frame = bytes(conn._firered_vad_buffer[:frame_bytes])
            del conn._firered_vad_buffer[:shift_bytes]
            audio_frame = np.frombuffer(frame, dtype=np.int16)
            result = self._detect_frame(conn, audio_frame)
            if not conn.client_have_voice:
                conn._firered_vad_diagnostic_frames += 1
                conn._firered_vad_diagnostic_max_prob = max(
                    conn._firered_vad_diagnostic_max_prob, result.raw_prob
                )
                pcm_rms = float(
                    np.sqrt(np.mean(audio_frame.astype(np.float32) ** 2))
                )
                conn._firered_vad_diagnostic_max_rms = max(
                    conn._firered_vad_diagnostic_max_rms, pcm_rms
                )

            if result.is_speech_start:
                conn._firered_vad_in_speech = True
                conn.client_have_voice = True
                getattr(conn, "logger", logger).bind(tag=TAG).info(
                    f"FireRedVAD语音起点: frame={result.frame_idx}, "
                    f"raw_prob={result.raw_prob:.3f}, "
                    f"smoothed_prob={result.smoothed_prob:.3f}"
                )
            if conn._firered_vad_in_speech and result.is_speech:
                current_have_voice = True
                conn.vad_last_voice_time = time.time() * 1000
            if result.is_speech_end:
                conn._firered_vad_in_speech = False
                conn.client_voice_stop = True
                getattr(conn, "logger", logger).bind(tag=TAG).info(
                    f"FireRedVAD语音终点: frame={result.frame_idx}, "
                    f"silence_frames={self.min_silence_frame}"
                )
                conn._firered_vad_diagnostic_started_at = time.monotonic()
                conn._firered_vad_diagnostic_frames = 0
                conn._firered_vad_diagnostic_max_prob = 0.0
                conn._firered_vad_diagnostic_max_rms = 0.0

        diagnostic_elapsed = (
            time.monotonic() - conn._firered_vad_diagnostic_started_at
        )
        if (
            not conn.client_have_voice
            and not conn._firered_vad_diagnostic_logged
            and diagnostic_elapsed >= _DIAGNOSTIC_INTERVAL_SECONDS
        ):
            getattr(conn, "logger", logger).bind(tag=TAG).info(
                "FireRedVAD未触发语音起点: "
                f"frames={conn._firered_vad_diagnostic_frames}, "
                f"max_raw_prob={conn._firered_vad_diagnostic_max_prob:.3f}, "
                f"max_pcm_rms={conn._firered_vad_diagnostic_max_rms:.1f}, "
                f"threshold={self.speech_threshold}, "
                f"required_consecutive_frames={self.min_speech_frame}"
            )
            conn._firered_vad_diagnostic_logged = True

        return current_have_voice
