ALTER TABLE `ai_device_proactive_preference`
    ADD COLUMN `previous_daily_limit` int DEFAULT NULL COMMENT 'today silent前的每日上限' AFTER `previous_mode`,
    DROP INDEX `uk_ai_device_proactive_preference_mac`,
    ADD KEY `idx_ai_device_proactive_preference_mac` (`mac_address`);

ALTER TABLE `ai_device_proactive_event`
    DROP INDEX `uk_ai_device_proactive_event_id`,
    DROP INDEX `uk_ai_device_proactive_event_dedupe`,
    ADD UNIQUE KEY `uk_ai_device_proactive_event_device_event` (`device_id`, `event_id`),
    ADD KEY `idx_ai_device_proactive_event_dedupe` (`device_id`, `dedupe_key`);
