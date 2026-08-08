ALTER TABLE `ai_device_proactive_preference`
    ADD COLUMN `previous_daily_limit` int DEFAULT NULL COMMENT 'today silent前的每日上限' AFTER `previous_mode`,
    DROP INDEX `uk_ai_device_proactive_preference_mac`,
    ADD KEY `idx_ai_device_proactive_preference_mac` (`mac_address`);

UPDATE `ai_device_proactive_preference`
SET `previous_daily_limit` = CASE `previous_mode`
        WHEN 'CONSERVATIVE' THEN 1
        WHEN 'ACTIVE' THEN 3
        WHEN 'AGGRESSIVE' THEN 5
        ELSE 5
    END
WHERE `mode` = 'TODAY_SILENT' AND `previous_daily_limit` IS NULL;

ALTER TABLE `ai_device_proactive_event`
    DROP INDEX `uk_ai_device_proactive_event_id`,
    DROP INDEX `uk_ai_device_proactive_event_dedupe`,
    ADD UNIQUE KEY `uk_ai_device_proactive_event_device_event` (`device_id`, `event_id`),
    ADD KEY `idx_ai_device_proactive_event_dedupe` (`device_id`, `dedupe_key`);
