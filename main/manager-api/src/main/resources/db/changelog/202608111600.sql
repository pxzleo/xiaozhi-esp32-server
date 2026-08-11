ALTER TABLE `ai_mobile_event`
    ADD COLUMN `processing_status` varchar(16) NOT NULL DEFAULT 'received' AFTER `status`,
    ADD COLUMN `category` varchar(32) DEFAULT NULL AFTER `reason_code`,
    ADD COLUMN `severity` varchar(8) DEFAULT NULL AFTER `category`,
    ADD COLUMN `confidence` decimal(5,4) DEFAULT NULL AFTER `severity`,
    ADD COLUMN `spoken_summary` varchar(120) DEFAULT NULL COMMENT '独立分类模型返回的受控短摘要' AFTER `confidence`,
    ADD COLUMN `proactive_event_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER `spoken_summary`,
    ADD COLUMN `processing_lease_owner` varchar(64) DEFAULT NULL AFTER `proactive_event_id`,
    ADD COLUMN `processing_lease_token` char(36) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL AFTER `processing_lease_owner`,
    ADD COLUMN `processing_lease_until` datetime(3) DEFAULT NULL AFTER `processing_lease_token`,
    ADD COLUMN `processing_attempt` int unsigned NOT NULL DEFAULT 0 AFTER `processing_lease_until`,
    ADD COLUMN `next_attempt_at` datetime(3) DEFAULT NULL AFTER `processing_attempt`,
    ADD COLUMN `processed_at` datetime(3) DEFAULT NULL AFTER `next_attempt_at`,
    ADD CONSTRAINT `chk_mobile_event_processing_status`
        CHECK (`processing_status` IN ('received','prefiltered','classified','ignored','converted','error')),
    ADD CONSTRAINT `chk_mobile_event_category`
        CHECK (`category` IS NULL OR `category` IN ('security','call','parcel','appointment','message','other','location')),
    ADD CONSTRAINT `chk_mobile_event_severity`
        CHECK (`severity` IS NULL OR `severity` IN ('low','medium','high','critical')),
    ADD CONSTRAINT `chk_mobile_event_confidence`
        CHECK (`confidence` IS NULL OR (`confidence` >= 0 AND `confidence` <= 1)),
    ADD KEY `idx_mobile_event_processing_due`
        (`processing_status`, `next_attempt_at`, `processing_lease_until`, `expires_at`),
    ADD KEY `idx_mobile_event_audit_filter`
        (`mobile_instance_id`, `event_type`, `processing_status`, `occurred_at`),
    ADD KEY `idx_mobile_event_proactive` (`proactive_event_id`);

ALTER TABLE `ai_device_proactive_event_dedupe`
    DROP CHECK `chk_proactive_event_dedupe_type`,
    ADD CONSTRAINT `chk_proactive_event_dedupe_type`
        CHECK (`event_type` IN ('WEATHER_ALERT', 'NEWS_ALERT', 'MOBILE_ALERT'));
