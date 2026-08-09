import os
import tempfile
import types
import unittest
from unittest.mock import Mock, patch

import torch

from core.providers.asr.base import ASRProviderBase, _pcm_quality_stats
from core.providers.asr import firered_aed
from core.providers.vad import firered_stream
from core.providers.firered_utils import import_firered_module
from core.connection import ConnectionHandler


class FakeAsrConfig:
    def __init__(self, **kwargs):
        self.values = kwargs


class FakeAsrModel:
    def transcribe(self, utterance_ids, wav_paths):
        return [
            {
                "uttid": utterance_ids[0],
                "text": "  播放周杰伦的稻香  ",
                "rtf": "0.0870",
                "wav": wav_paths[0],
            }
        ]


class FakeFireRedAsrModule:
    FireRedAsr2Config = FakeAsrConfig

    class FireRedAsr2:
        load_count = 0

        @classmethod
        def from_pretrained(cls, asr_type, model_dir, config):
            cls.load_count += 1
            if asr_type != "aed":
                raise AssertionError("必须加载AED模型")
            if not os.path.isdir(model_dir):
                raise AssertionError("模型目录必须存在")
            if config.values["beam_size"] != 3:
                raise AssertionError("配置没有透传")
            return FakeAsrModel()


class FakeVadConfig:
    def __init__(self, **kwargs):
        self.values = kwargs


class FakeVadPostprocessor:
    def __init__(self, *args):
        self.frame_count = 0

    def process_one_frame(self, raw_prob):
        self.frame_count += 1
        return types.SimpleNamespace(
            frame_idx=self.frame_count,
            is_speech=raw_prob >= 0.5,
            raw_prob=float(raw_prob),
            smoothed_prob=float(raw_prob),
            is_speech_start=self.frame_count == 1 and raw_prob >= 0.5,
            is_speech_end=self.frame_count == 2 and raw_prob < 0.5,
        )


class FakeAudioFeat:
    def extract(self, audio_frame):
        if len(audio_frame) != 400:
            raise AssertionError("必须按官方25ms帧长送入")
        return torch.zeros((1, 80), dtype=torch.float32), 0.025


class FakeVadModel:
    def forward(self, feat, caches=None):
        probability = 0.9 if caches is None else 0.1
        next_cache = 1 if caches is None else caches + 1
        return torch.tensor([probability]), next_cache


class FakeFireRedVadModule:
    FireRedStreamVadConfig = FakeVadConfig

    class FireRedStreamVad:
        @classmethod
        def from_pretrained(cls, model_dir, config):
            if not os.path.isdir(model_dir):
                raise AssertionError("模型目录必须存在")
            return types.SimpleNamespace(
                audio_feat=FakeAudioFeat(),
                vad_model=FakeVadModel(),
                postprocessor=FakeVadPostprocessor(),
            )


def new_connection():
    return types.SimpleNamespace(
        client_listen_mode="auto",
        client_have_voice=False,
        client_voice_stop=False,
        vad_last_voice_time=0.0,
    )


def create_model_files(directory, file_names):
    for file_name in file_names:
        with open(os.path.join(directory, file_name), "wb"):
            pass


class FireRedAsrProviderTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        FakeFireRedAsrModule.FireRedAsr2.load_count = 0
        firered_aed.clear_model_cache()

    def tearDown(self):
        firered_aed.clear_model_cache()

    def test_pcm_quality_stats_reports_level_clipping_and_zero_ratio(self):
        samples = torch.tensor(
            [0, 0, 1000, -1000, 32767, -32768], dtype=torch.int16
        ).numpy().astype("<i2", copy=False).tobytes()

        stats = _pcm_quality_stats(samples)

        self.assertEqual(6, stats["sample_count"])
        self.assertEqual(32768, stats["pcm_peak"])
        self.assertAlmostEqual(2 / 6, stats["clipping_ratio"], places=4)
        self.assertAlmostEqual(2 / 6, stats["zero_ratio"], places=4)
        self.assertGreater(stats["pcm_rms"], 18000)

    async def test_asr_result_log_contains_pcm_quality_stats(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                provider = firered_aed.ASRProvider(
                    {"model_dir": model_dir, "output_dir": output_dir}, True
                )
            pcm = torch.tensor([0, 1000, -1000, 32767], dtype=torch.int16).numpy().astype(
                "<i2", copy=False
            ).tobytes()

            with patch("core.providers.asr.base.logger") as safe_logger:
                await provider.speech_to_text_wrapper([pcm], "quality-session")

        messages = [
            call.args[0] for call in safe_logger.bind.return_value.info.call_args_list
        ]
        result_log = next(message for message in messages if "ASR处理结果" in message)
        self.assertIn("pcm_rms=", result_log)
        self.assertIn("pcm_peak=32767", result_log)
        self.assertIn("clipping_ratio=0.2500", result_log)
        self.assertIn("zero_ratio=0.2500", result_log)

    async def test_quality_stats_failure_does_not_override_asr_result(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                provider = firered_aed.ASRProvider(
                    {"model_dir": model_dir, "output_dir": output_dir}, True
                )
            with patch(
                "core.providers.asr.base._pcm_quality_stats",
                side_effect=RuntimeError("quality failed"),
            ):
                text, path = await provider.speech_to_text_wrapper(
                    [bytes(32000)], "quality-failure-session"
                )

        self.assertEqual("播放周杰伦的稻香", text)
        self.assertIsNone(path)

    async def test_transcribes_temp_wav_with_aed(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            config = {
                "model_dir": model_dir,
                "output_dir": output_dir,
                "use_gpu": False,
                "use_half": False,
                "beam_size": 3,
            }
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                provider = firered_aed.ASRProvider(config, True)

            artifacts = ASRProviderBase.AudioArtifacts(
                pcm_frames=[b"audio"],
                pcm_bytes=b"audio",
                file_path=None,
                temp_path="/tmp/input.wav",
            )
            text, path = await provider.speech_to_text(
                [b"audio"], "session-1", artifacts
            )

            self.assertEqual("播放周杰伦的稻香", text)
            self.assertEqual("/tmp/input.wav", path)
            self.assertTrue(provider.requires_file())
            self.assertTrue(provider.prefers_temp_file())

    async def test_rejects_invalid_result_shape(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                provider = firered_aed.ASRProvider(
                    {"model_dir": model_dir, "output_dir": output_dir}, True
                )
            provider.model.transcribe = lambda *_: []
            artifacts = ASRProviderBase.AudioArtifacts([], b"x", None, "/tmp/x.wav")

            with self.assertRaisesRegex(RuntimeError, "无效结果数量"):
                await provider.speech_to_text([], "session-2", artifacts)

    async def test_rejects_empty_text_returned_after_official_runtime_error(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                provider = firered_aed.ASRProvider(
                    {"model_dir": model_dir, "output_dir": output_dir}, True
                )
            provider.model.transcribe = lambda *_: [{"text": "", "uttid": "x"}]
            artifacts = ASRProviderBase.AudioArtifacts([], b"x", None, "/tmp/x.wav")

            with self.assertRaisesRegex(RuntimeError, "返回空文本"):
                await provider.speech_to_text([], "session-3", artifacts)

    async def test_reuses_aed_model_and_inference_lock_for_same_configuration(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            config = {
                "model_dir": model_dir,
                "output_dir": output_dir,
                "use_gpu": False,
                "use_half": False,
                "beam_size": 3,
            }
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                first = firered_aed.ASRProvider(config, True)
                second = firered_aed.ASRProvider(config, True)

            self.assertIs(first.model, second.model)
            self.assertIs(first._inference_lock, second._inference_lock)
            self.assertEqual(1, FakeFireRedAsrModule.FireRedAsr2.load_count)

    async def test_rejects_hot_switch_to_a_second_model_configuration(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            config = {
                "model_dir": model_dir,
                "output_dir": output_dir,
                "use_gpu": False,
                "use_half": False,
                "beam_size": 3,
            }
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                firered_aed.ASRProvider(config, True)
                changed_config = dict(config, softmax_smoothing=1.5)
                with self.assertRaisesRegex(RuntimeError, "不能热切换"):
                    firered_aed.ASRProvider(changed_config, True)

            self.assertEqual(1, FakeFireRedAsrModule.FireRedAsr2.load_count)

    async def test_wrapper_turns_aed_empty_result_into_normal_empty_text(self):
        with tempfile.TemporaryDirectory() as model_dir, tempfile.TemporaryDirectory() as output_dir:
            create_model_files(
                model_dir,
                ("cmvn.ark", "model.pth.tar", "dict.txt", "train_bpe1000.model"),
            )
            with patch.object(
                firered_aed,
                "import_firered_module",
                return_value=FakeFireRedAsrModule,
            ):
                provider = firered_aed.ASRProvider(
                    {"model_dir": model_dir, "output_dir": output_dir}, True
                )
            provider.model.transcribe = lambda *_: [{"text": "", "uttid": "x"}]

            text, path = await provider.speech_to_text_wrapper(
                [bytes(32000)], "session-empty"
            )

            self.assertEqual("", text)
            self.assertIsNone(path)


class FireRedVadProviderTest(unittest.TestCase):
    def setUp(self):
        self.model_dir = tempfile.TemporaryDirectory()
        create_model_files(self.model_dir.name, ("cmvn.ark", "model.pth.tar"))
        config = {
            "model_dir": self.model_dir.name,
            "use_gpu": False,
            "min_silence_duration_ms": 500,
        }
        patcher = patch.object(
            firered_stream,
            "import_firered_module",
            return_value=FakeFireRedVadModule,
        )
        self.addCleanup(patcher.stop)
        patcher.start()
        self.provider = firered_stream.VADProvider(config)

    def tearDown(self):
        self.model_dir.cleanup()

    def test_stream_state_is_isolated_per_connection(self):
        first = new_connection()
        second = new_connection()
        frame = bytes(400 * 2)

        self.assertTrue(self.provider.is_vad(first, frame))
        self.assertTrue(self.provider.is_vad(second, frame))
        self.assertIsNot(
            first._firered_vad_postprocessor,
            second._firered_vad_postprocessor,
        )
        self.assertEqual(1, first._firered_vad_model_caches)
        self.assertEqual(1, second._firered_vad_model_caches)

    def test_stream_end_sets_voice_stop_and_release_clears_state(self):
        conn = new_connection()
        self.assertTrue(self.provider.is_vad(conn, bytes(400 * 2)))
        self.assertFalse(self.provider.is_vad(conn, bytes(160 * 2)))
        self.assertTrue(conn.client_voice_stop)

        self.provider.release_conn_resources(conn)
        self.assertFalse(hasattr(conn, "_firered_vad_buffer"))
        self.assertFalse(hasattr(conn, "_firered_vad_model_caches"))
        self.assertFalse(hasattr(conn, "_firered_vad_postprocessor"))
        self.assertFalse(hasattr(conn, "_firered_vad_in_speech"))
        self.assertFalse(hasattr(conn, "_firered_vad_diagnostic_started_at"))
        self.assertFalse(hasattr(conn, "_firered_vad_diagnostic_frames"))
        self.assertFalse(hasattr(conn, "_firered_vad_diagnostic_max_prob"))
        self.assertFalse(hasattr(conn, "_firered_vad_diagnostic_max_rms"))
        self.assertFalse(hasattr(conn, "_firered_vad_diagnostic_logged"))

    def test_reset_conn_state_drops_partial_frame_and_model_state(self):
        conn = new_connection()
        self.assertTrue(self.provider.is_vad(conn, bytes(400 * 2)))
        conn._firered_vad_buffer.extend(b"partial")

        self.provider.reset_conn_state(conn)

        self.assertFalse(hasattr(conn, "_firered_vad_buffer"))
        self.assertFalse(hasattr(conn, "_firered_vad_model_caches"))
        self.assertFalse(hasattr(conn, "_firered_vad_postprocessor"))

    def test_manual_mode_does_not_initialize_stream_state(self):
        conn = new_connection()
        conn.client_listen_mode = "manual"
        self.assertTrue(self.provider.is_vad(conn, b""))
        self.assertFalse(hasattr(conn, "_firered_vad_buffer"))

    def test_default_keeps_official_80ms_start_gate_and_700ms_silence(self):
        provider = firered_stream.VADProvider(
            {"model_dir": self.model_dir.name, "use_gpu": False}
        )

        self.assertEqual(8, provider.min_speech_frame)
        self.assertEqual(70, provider.min_silence_frame)

    def test_connection_audio_reset_also_resets_provider_state(self):
        vad = Mock()
        conn = types.SimpleNamespace(
            vad=vad,
            client_audio_buffer=bytearray(b"pcm"),
            client_have_voice=True,
            client_voice_stop=True,
            client_voice_window=[True],
            last_is_voice=True,
            vad_last_voice_time=123.0,
            asr_audio=[b"pcm"],
            logger=Mock(),
        )

        ConnectionHandler.reset_audio_states(conn)

        vad.reset_conn_state.assert_called_once_with(conn)
        self.assertEqual(bytearray(), conn.client_audio_buffer)
        self.assertEqual([], conn.asr_audio)

    def test_logs_bounded_diagnostic_when_speech_start_is_not_detected(self):
        conn = new_connection()
        no_speech = types.SimpleNamespace(
            frame_idx=1,
            is_speech=False,
            raw_prob=0.2,
            smoothed_prob=0.2,
            is_speech_start=False,
            is_speech_end=False,
        )
        with patch.object(
            self.provider, "_detect_frame", return_value=no_speech
        ), patch.object(
            firered_stream.time,
            "monotonic",
            side_effect=(0.0, 5.1, 5.1),
        ), patch.object(firered_stream.logger, "bind") as bind:
            self.assertFalse(self.provider.is_vad(conn, bytes(400 * 2)))

        message = bind.return_value.info.call_args.args[0]
        self.assertIn("FireRedVAD未触发语音起点", message)
        self.assertIn("max_raw_prob=0.200", message)
        self.assertTrue(conn._firered_vad_diagnostic_logged)


class FireRedConfigurationTest(unittest.TestCase):
    def test_invalid_source_directory_is_explicit(self):
        with self.assertRaisesRegex(FileNotFoundError, "源码目录无效"):
            import_firered_module("/path/that/does/not/exist", "fireredvad")

    def test_invalid_boolean_is_rejected(self):
        with tempfile.TemporaryDirectory() as model_dir:
            create_model_files(model_dir, ("cmvn.ark", "model.pth.tar"))
            with self.assertRaisesRegex(ValueError, "use_gpu 必须是布尔值"):
                firered_stream.VADProvider(
                    {"model_dir": model_dir, "use_gpu": "sometimes"}
                )

    def test_loaded_module_from_another_source_is_rejected(self):
        with tempfile.TemporaryDirectory() as source_dir:
            os.makedirs(os.path.join(source_dir, "fireredasr2s"))
            foreign_module = types.SimpleNamespace(__file__="/tmp/foreign/fireredvad.py")
            with patch(
                "core.providers.firered_utils.importlib.import_module",
                return_value=foreign_module,
            ):
                with self.assertRaisesRegex(RuntimeError, "不能混用多个"):
                    import_firered_module(source_dir, "fireredvad")


if __name__ == "__main__":
    unittest.main()
