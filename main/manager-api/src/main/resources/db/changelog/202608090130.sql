CREATE TABLE `ai_device_proactive_event_dedupe` (
    `device_id` varchar(32) NOT NULL COMMENT 'ai_device.id',
    `event_type` varchar(32) NOT NULL COMMENT 'WEATHER_ALERT或NEWS_ALERT',
    `dedupe_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '去重键SHA-256',
    `last_event_id` varchar(64) DEFAULT NULL COMMENT '最近创建的权威事件ID',
    `last_created_at` datetime DEFAULT NULL COMMENT '最近创建时间，使用数据库时钟',
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`device_id`, `event_type`, `dedupe_hash`),
    KEY `idx_proactive_event_dedupe_recent` (`last_created_at`),
    CONSTRAINT `chk_proactive_event_dedupe_type`
        CHECK (`event_type` IN ('WEATHER_ALERT', 'NEWS_ALERT')),
    CONSTRAINT `fk_proactive_event_dedupe_device`
        FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外界监测事件滚动窗口权威去重账本';
