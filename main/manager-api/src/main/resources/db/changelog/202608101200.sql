CREATE TABLE `ai_mobile_instance` (
    `mobile_instance_id` varchar(36) NOT NULL COMMENT '服务端生成的手机实例ID',
    `device_id` varchar(32) NOT NULL COMMENT '复用ai_device绑定与智能体配置',
    `user_id` bigint NOT NULL,
    `installation_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端随机且可重置UUID，非硬件标识',
    `agent_id` varchar(32) NOT NULL,
    `platform` varchar(16) NOT NULL,
    `app_version` varchar(20) NOT NULL,
    `capabilities` varchar(255) NOT NULL,
    `credential_hash` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'SHA-256，不保存明文凭据',
    `credential_version` int unsigned NOT NULL DEFAULT 1 COMMENT '每次凭据轮换单调递增',
    `revoked_at` datetime DEFAULT NULL,
    `last_connected_at` datetime DEFAULT NULL,
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`mobile_instance_id`),
    UNIQUE KEY `uk_mobile_instance_device` (`device_id`),
    UNIQUE KEY `uk_mobile_instance_owner_installation` (`user_id`, `installation_id`),
    CONSTRAINT `fk_mobile_instance_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_mobile_instance_platform` CHECK (`platform` = 'android')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可撤销的手机小智实例';

CREATE TABLE `ai_mobile_message_receipt` (
    `mobile_instance_id` varchar(36) NOT NULL,
    `message_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `status` varchar(16) NOT NULL,
    `claim_token` char(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `lease_expires_at` datetime DEFAULT NULL,
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`mobile_instance_id`, `message_id`),
    KEY `idx_mobile_message_receipt_created` (`created_at`),
    CONSTRAINT `fk_mobile_message_receipt_instance`
        FOREIGN KEY (`mobile_instance_id`) REFERENCES `ai_mobile_instance` (`mobile_instance_id`) ON DELETE CASCADE,
    CONSTRAINT `chk_mobile_message_receipt_status` CHECK (`status` IN ('PROCESSING', 'ACCEPTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='手机文字消息跨重连幂等接收账本';
