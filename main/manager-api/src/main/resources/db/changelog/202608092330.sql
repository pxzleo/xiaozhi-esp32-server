-- 仅把首版 FireRedVAD 的激进默认值迁移到实机验证后的稳健值；用户自定义值不覆盖。
UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
        `config_json`,
        '$.min_speech_frame', 20,
        '$.min_silence_duration_ms', 700
    ),
    `update_date` = NOW()
WHERE `id` = 'VAD_FireRedVAD'
  AND JSON_EXTRACT(`config_json`, '$.min_speech_frame') = 8
  AND JSON_EXTRACT(`config_json`, '$.min_silence_duration_ms') = 500;
