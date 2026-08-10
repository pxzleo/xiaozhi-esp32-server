CREATE TABLE `ai_mobile_event` (
    `mobile_instance_id` varchar(36) NOT NULL,
    `event_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `dedupe_key` varchar(72) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `event_type` varchar(64) NOT NULL,
    `source_package` varchar(200) NOT NULL,
    `source_channel` varchar(100) DEFAULT NULL,
    `event_state` varchar(16) NOT NULL,
    `summary` varchar(200) NOT NULL COMMENT '仅保存手机客户端已脱敏候选摘要',
    `entities_json` json NOT NULL,
    `evidence_json` json NOT NULL,
    `privacy_level` varchar(16) NOT NULL,
    `status` varchar(16) NOT NULL,
    `reason_code` varchar(64) DEFAULT NULL,
    `occurred_at` datetime(3) NOT NULL,
    `expires_at` datetime(3) NOT NULL,
    `created_at` datetime(3) NOT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`mobile_instance_id`, `event_id`),
    UNIQUE KEY `uk_mobile_event_state_flow` (`mobile_instance_id`, `dedupe_key`),
    KEY `idx_mobile_event_status_updated` (`mobile_instance_id`, `status`, `updated_at`),
    CONSTRAINT `fk_mobile_event_instance` FOREIGN KEY (`mobile_instance_id`)
        REFERENCES `ai_mobile_instance` (`mobile_instance_id`) ON DELETE CASCADE,
    CONSTRAINT `chk_mobile_event_status` CHECK (`status` IN ('acknowledged','deduped','rejected','expired')),
    CONSTRAINT `chk_mobile_event_state` CHECK (`event_state` IN ('posted','updated','removed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机脱敏候选事件与接收审计';

CREATE TABLE `ai_mobile_event_audit` (
    `id` bigint unsigned NOT NULL AUTO_INCREMENT,
    `mobile_instance_id` varchar(36) NOT NULL,
    `event_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `status` varchar(16) NOT NULL,
    `reason_code` varchar(64) DEFAULT NULL,
    `created_at` datetime(3) NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_mobile_event_audit_instance` (`mobile_instance_id`, `id`),
    CONSTRAINT `fk_mobile_event_audit_instance` FOREIGN KEY (`mobile_instance_id`)
        REFERENCES `ai_mobile_instance` (`mobile_instance_id`) ON DELETE CASCADE,
    CONSTRAINT `chk_mobile_event_audit_status` CHECK (`status` IN ('acknowledged','deduped','rejected','expired'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机候选事件逐次接收结果审计，不保存正文';
