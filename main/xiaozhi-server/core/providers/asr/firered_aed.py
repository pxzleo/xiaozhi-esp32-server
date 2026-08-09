import asyncio
import os
import threading
import time
from typing import List, Optional, Tuple

from config.logger import setup_logging
from core.providers.asr.base import ASRProviderBase
from core.providers.asr.dto.dto import InterfaceType
from core.providers.firered_utils import (
    import_firered_module,
    parse_bool,
    parse_float,
    parse_int,
    require_model_dir,
    require_model_files,
)

TAG = __name__
logger = setup_logging()
_MODEL_CACHE = {}
_MODEL_CACHE_LOCK = threading.Lock()


def clear_model_cache():
    """清理进程级模型缓存；仅供测试和进程关闭后显式回收使用。"""
    with _MODEL_CACHE_LOCK:
        _MODEL_CACHE.clear()


class ASRProvider(ASRProviderBase):
    def __init__(self, config: dict, delete_audio_file: bool):
        super().__init__()
        self.interface_type = InterfaceType.LOCAL
        self.output_dir = config.get("output_dir", "tmp/")
        self.delete_audio_file = delete_audio_file
        os.makedirs(self.output_dir, exist_ok=True)

        source_dir = config.get("source_dir", "")
        model_dir = require_model_dir(
            config.get("model_dir"), "FireRedASR2-AED"
        )
        require_model_files(
            model_dir,
            "FireRedASR2-AED",
            ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
        )
        use_gpu = parse_bool(config.get("use_gpu", False), "use_gpu")
        use_half = parse_bool(config.get("use_half", False), "use_half")
        beam_size = parse_int(config.get("beam_size", 3), "beam_size", 1, 20)
        softmax_smoothing = parse_float(
            config.get("softmax_smoothing", 1.25),
            "softmax_smoothing",
            0.1,
            10.0,
        )
        length_penalty = parse_float(
            config.get("aed_length_penalty", 0.6),
            "aed_length_penalty",
            -10.0,
            10.0,
        )

        firered_asr = import_firered_module(source_dir, "fireredasr2")
        if use_gpu:
            import torch

            if not torch.cuda.is_available():
                raise RuntimeError("FireRedASR2-AED 配置了 use_gpu，但 CUDA 不可用")

        model_config = firered_asr.FireRedAsr2Config(
            use_gpu=use_gpu,
            use_half=use_half,
            beam_size=beam_size,
            nbest=1,
            softmax_smoothing=softmax_smoothing,
            aed_length_penalty=length_penalty,
        )
        cache_key = (
            os.path.realpath(source_dir),
            os.path.realpath(model_dir),
            use_gpu,
            use_half,
            beam_size,
            softmax_smoothing,
            length_penalty,
        )
        load_started_at = time.monotonic()
        with _MODEL_CACHE_LOCK:
            cached = _MODEL_CACHE.get(cache_key)
            cache_hit = cached is not None
            if cached is None:
                if _MODEL_CACHE:
                    raise RuntimeError(
                        "同一服务进程不能热切换 FireRedASR2-AED 配置；"
                        "请保存配置后重启 xiaozhi-server，避免旧模型占用显存"
                    )
                cached = (
                    firered_asr.FireRedAsr2.from_pretrained(
                        "aed", model_dir, model_config
                    ),
                    threading.Lock(),
                )
                _MODEL_CACHE[cache_key] = cached
        self.model, self._inference_lock = cached
        logger.bind(tag=TAG).info(
            f"FireRedASR2-AED 初始化完成，device={'cuda' if use_gpu else 'cpu'}，"
            f"half={use_half}，beam_size={beam_size}，cache_hit={cache_hit}，"
            f"load_seconds={time.monotonic() - load_started_at:.3f}"
        )

    def requires_file(self) -> bool:
        return True

    def prefers_temp_file(self) -> bool:
        return True

    def _transcribe(self, session_id: str, wav_path: str):
        with self._inference_lock:
            return self.model.transcribe([session_id], [wav_path])

    async def speech_to_text(
        self,
        opus_data: List[bytes],
        session_id: str,
        artifacts: Optional[ASRProviderBase.AudioArtifacts] = None,
    ) -> Tuple[Optional[str], Optional[str]]:
        if artifacts is None or not artifacts.temp_path:
            raise ValueError("FireRedASR2-AED 缺少待识别 WAV 音频")

        start_time = time.monotonic()
        results = await asyncio.to_thread(
            self._transcribe, session_id, artifacts.temp_path
        )
        if not isinstance(results, list) or len(results) != 1:
            raise RuntimeError("FireRedASR2-AED 返回了无效结果数量")
        result = results[0]
        if not isinstance(result, dict) or not isinstance(result.get("text"), str):
            raise RuntimeError("FireRedASR2-AED 返回结果缺少 text")

        text = result["text"].strip()
        if not text:
            raise RuntimeError(
                "FireRedASR2-AED 返回空文本；官方运行时可能在音频预处理或推理失败时返回空结果"
            )
        logger.bind(tag=TAG).info(
            f"语音识别耗时: {time.monotonic() - start_time:.3f}s | "
            f"RTF: {result.get('rtf', 'unknown')} | 结果: {text}"
        )
        return text, artifacts.temp_path
