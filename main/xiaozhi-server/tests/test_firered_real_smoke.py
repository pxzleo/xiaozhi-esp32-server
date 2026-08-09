import asyncio
import os
import types
import unittest

import numpy as np
import soundfile as sf

from core.providers.asr.base import ASRProviderBase
from core.providers.asr.firered_aed import ASRProvider as FireRedAsrProvider
from core.providers.vad.firered_stream import VADProvider as FireRedVadProvider


SOURCE_DIR = os.environ.get("FIRERED_SOURCE_DIR", "")
ASR_MODEL_DIR = os.environ.get("FIRERED_ASR_MODEL_DIR", "")
VAD_MODEL_DIR = os.environ.get("FIRERED_VAD_MODEL_DIR", "")
SMOKE_ENABLED = all((SOURCE_DIR, ASR_MODEL_DIR, VAD_MODEL_DIR))


@unittest.skipUnless(
    SMOKE_ENABLED,
    "需要设置 FIRERED_SOURCE_DIR/FIRERED_ASR_MODEL_DIR/FIRERED_VAD_MODEL_DIR",
)
class FireRedRealSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.wav_path = os.environ.get(
            "FIRERED_SMOKE_WAV", os.path.join(SOURCE_DIR, "assets", "hello_zh.wav")
        )
        if not os.path.isfile(cls.wav_path):
            raise FileNotFoundError(f"真实冒烟音频不存在: {cls.wav_path}")
        cls.use_gpu = os.environ.get("FIRERED_USE_GPU", "false")

    def test_real_stream_vad_and_aed_transcription(self):
        samples, sample_rate = sf.read(self.wav_path, dtype="int16")
        if sample_rate != 16000 or samples.ndim != 1:
            raise ValueError("真实冒烟音频必须是16kHz 16-bit单声道")

        vad = FireRedVadProvider(
            {
                "source_dir": SOURCE_DIR,
                "model_dir": VAD_MODEL_DIR,
                "use_gpu": self.use_gpu,
            }
        )
        self.assertEqual(8, vad.min_speech_frame)
        self.assertEqual(70, vad.min_silence_frame)

        postprocessor = vad._new_postprocessor()
        for _ in range(7):
            self.assertFalse(postprocessor.process_one_frame(0.9).is_speech_start)
        self.assertTrue(postprocessor.process_one_frame(0.9).is_speech_start)
        classified_silence_frames = 0
        for _ in range(100):
            result = postprocessor.process_one_frame(0.0)
            if not result.is_speech:
                classified_silence_frames += 1
            if result.is_speech_end:
                break
        self.assertTrue(result.is_speech_end)
        self.assertEqual(70, classified_silence_frames)

        conn = types.SimpleNamespace(
            client_listen_mode="auto",
            client_have_voice=False,
            client_voice_stop=False,
            vad_last_voice_time=0.0,
        )
        pcm = samples.astype("<i2", copy=False).tobytes()
        trailing_silence = np.zeros(16000, dtype="<i2").tobytes()
        for offset in range(0, len(pcm), 1920):
            vad.is_vad(conn, pcm[offset : offset + 1920])
        for offset in range(0, len(trailing_silence), 1920):
            vad.is_vad(conn, trailing_silence[offset : offset + 1920])
            if conn.client_voice_stop:
                break
        self.assertTrue(conn.client_have_voice)
        self.assertTrue(conn.client_voice_stop)

        asr = FireRedAsrProvider(
            {
                "source_dir": SOURCE_DIR,
                "model_dir": ASR_MODEL_DIR,
                "output_dir": "/tmp",
                "use_gpu": self.use_gpu,
                "use_half": self.use_gpu,
            },
            True,
        )
        artifacts = ASRProviderBase.AudioArtifacts(
            pcm_frames=[pcm],
            pcm_bytes=pcm,
            file_path=self.wav_path,
            temp_path=self.wav_path,
        )
        text, _ = asyncio.run(
            asr.speech_to_text([pcm], "firered-real-smoke", artifacts)
        )
        self.assertTrue(text.strip())


if __name__ == "__main__":
    unittest.main()
