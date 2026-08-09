-- 新增 FireRedVAD 与 FireRedASR2-AED 可选模型供应器及模型配置。
INSERT INTO `ai_model_provider`
    (`id`, `model_type`, `provider_code`, `name`, `fields`, `sort`,
     `creator`, `create_date`, `updater`, `update_date`)
VALUES
    ('SYSTEM_VAD_FireRedVAD', 'VAD', 'firered_stream',
     'FireRedVAD流式语音活动检测',
     JSON_ARRAY(
         JSON_OBJECT('key', 'source_dir', 'label', '源码目录', 'type', 'string'),
         JSON_OBJECT('key', 'model_dir', 'label', '模型目录', 'type', 'string'),
         JSON_OBJECT('key', 'use_gpu', 'label', '使用GPU', 'type', 'boolean'),
         JSON_OBJECT('key', 'speech_threshold', 'label', '语音检测阈值', 'type', 'number'),
         JSON_OBJECT('key', 'smooth_window_size', 'label', '平滑窗口帧数', 'type', 'number'),
         JSON_OBJECT('key', 'pad_start_frame', 'label', '语音起点前补帧数', 'type', 'number'),
         JSON_OBJECT('key', 'min_speech_frame', 'label', '最短语音帧数', 'type', 'number'),
         JSON_OBJECT('key', 'max_speech_frame', 'label', '最长语音帧数', 'type', 'number'),
         JSON_OBJECT('key', 'min_silence_duration_ms', 'label', '最小静音时长(ms)', 'type', 'number')
     ),
     2, 1, NOW(), 1, NOW()),
    ('SYSTEM_ASR_FireRedASR2AED', 'ASR', 'firered_aed',
     'FireRedASR2-AED语音识别',
     JSON_ARRAY(
         JSON_OBJECT('key', 'source_dir', 'label', '源码目录', 'type', 'string'),
         JSON_OBJECT('key', 'model_dir', 'label', '模型目录', 'type', 'string'),
         JSON_OBJECT('key', 'output_dir', 'label', '输出目录', 'type', 'string'),
         JSON_OBJECT('key', 'use_gpu', 'label', '使用GPU', 'type', 'boolean'),
         JSON_OBJECT('key', 'use_half', 'label', '半精度推理', 'type', 'boolean'),
         JSON_OBJECT('key', 'beam_size', 'label', '束搜索宽度', 'type', 'number'),
         JSON_OBJECT('key', 'softmax_smoothing', 'label', 'Softmax平滑系数', 'type', 'number'),
         JSON_OBJECT('key', 'aed_length_penalty', 'label', '长度惩罚', 'type', 'number')
     ),
     20, 1, NOW(), 1, NOW());

INSERT INTO `ai_model_config`
    (`id`, `model_type`, `model_code`, `model_name`, `is_default`, `is_enabled`,
     `config_json`, `doc_link`, `remark`, `sort`,
     `creator`, `create_date`, `updater`, `update_date`)
VALUES
    ('VAD_FireRedVAD', 'VAD', 'FireRedVAD', 'FireRedVAD流式语音活动检测',
     0, 1,
     JSON_OBJECT(
         'type', 'firered_stream',
         'source_dir', 'models/FireRedASR2S',
         'model_dir', 'models/FireRedASR2S/pretrained_models/FireRedVAD/Stream-VAD',
         'use_gpu', FALSE,
         'speech_threshold', 0.5,
         'smooth_window_size', 5,
         'pad_start_frame', 5,
         'min_speech_frame', 8,
         'max_speech_frame', 2000,
         'min_silence_duration_ms', 500
     ),
     'https://github.com/FireRedTeam/FireRedASR2S',
     '本地流式VAD；先按docs/firered-asr2s-integration.md安装源码、依赖和模型。',
     2, 1, NOW(), 1, NOW()),
    ('ASR_FireRedASR2AED', 'ASR', 'FireRedASR2AED',
     'FireRedASR2-AED语音识别',
     0, 1,
     JSON_OBJECT(
         'type', 'firered_aed',
         'source_dir', 'models/FireRedASR2S',
         'model_dir', 'models/FireRedASR2S/pretrained_models/FireRedASR2-AED',
         'output_dir', 'tmp/',
         'use_gpu', FALSE,
         'use_half', FALSE,
         'beam_size', 3,
         'softmax_smoothing', 1.25,
         'aed_length_penalty', 0.6
     ),
     'https://github.com/FireRedTeam/FireRedASR2S',
     '本地整句AED识别；默认CPU，启用GPU前必须确认显存充足。',
     20, 1, NOW(), 1, NOW());
