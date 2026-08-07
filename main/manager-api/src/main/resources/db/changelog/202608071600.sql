CREATE TABLE `ai_device_netease_auth` (
    `device_id` varchar(64) NOT NULL COMMENT '设备MAC地址',
    `credential_ciphertext` text DEFAULT NULL COMMENT '加密后的网易云凭证',
    `provider_user_id` varchar(64) DEFAULT NULL,
    `nickname` varchar(255) DEFAULT NULL,
    `avatar_url` varchar(1024) DEFAULT NULL,
    `status` varchar(32) NOT NULL,
    `revoke_reason` varchar(255) DEFAULT NULL,
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    `revoked_at` datetime DEFAULT NULL,
    `version` int NOT NULL DEFAULT 0,
    PRIMARY KEY (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备网易云音乐授权';

CREATE TABLE `ai_device_netease_session` (
    `session_id` varchar(36) NOT NULL,
    `device_id` varchar(64) NOT NULL,
    `qr_key_ciphertext` text DEFAULT NULL,
    `qr_content` text DEFAULT NULL,
    `status` varchar(32) NOT NULL,
    `failure_reason` varchar(255) DEFAULT NULL,
    `expires_at` datetime NOT NULL,
    `active_slot` varchar(64) DEFAULT NULL COMMENT '活动会话等于device_id，终态置NULL',
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`session_id`),
    UNIQUE KEY `uk_ai_device_netease_session_active` (`active_slot`),
    KEY `idx_ai_device_netease_session_device` (`device_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备网易云二维码登录会话';

UPDATE `ai_model_provider`
SET `fields` = JSON_REMOVE(`fields`,
    REPLACE(JSON_UNQUOTE(JSON_SEARCH(`fields`, 'one', 'cookie', NULL, '$[*].key')), '.key', ''))
WHERE `provider_code` = 'play_netease_music'
  AND JSON_SEARCH(`fields`, 'one', 'cookie', NULL, '$[*].key') IS NOT NULL;

UPDATE `ai_agent_plugin_mapping`
SET `param_info` = CASE
    WHEN JSON_VALID(`param_info`) THEN JSON_REMOVE(`param_info`, '$.cookie')
    ELSE `param_info`
END
WHERE `plugin_id` = 'SYSTEM_PLUGIN_NETEASE_MUSIC';
