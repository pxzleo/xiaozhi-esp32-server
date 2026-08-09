-- 恢复 FireRedVAD 官方起点门槛，保留较长结尾静音；只修正上一版的完整默认组合。
UPDATE `ai_model_config`
SET `config_json` = JSON_SET(
        `config_json`,
        '$.min_speech_frame', 8
    ),
    `update_date` = NOW()
WHERE `id` = 'VAD_FireRedVAD'
  AND JSON_EXTRACT(`config_json`, '$.min_speech_frame') = 20
  AND JSON_EXTRACT(`config_json`, '$.min_silence_duration_ms') = 700
  AND EXISTS (
      SELECT 1
      FROM `DATABASECHANGELOG` AS `dc`
      WHERE `dc`.`ID` = '202608092330'
        AND `dc`.`AUTHOR` = 'xu'
        AND `ai_model_config`.`update_date` <= `dc`.`DATEEXECUTED`
        AND TIMESTAMPDIFF(
            SECOND,
            `ai_model_config`.`update_date`,
            `dc`.`DATEEXECUTED`
        ) BETWEEN 0 AND 60
  );
