ALTER TABLE `ai_device_proactive_event`
    ADD COLUMN `delivery_group_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '同一用户跨手机和音箱投递竞争键' AFTER `dedupe_key`,
    ADD COLUMN `delivery_group_window_hours` int unsigned NOT NULL DEFAULT 0
        COMMENT '0表示同一事件组投递后永久抑制；正数表示滚动过程再提醒窗口' AFTER `delivery_group_key`,
    ADD COLUMN `mobile_terminal_status` varchar(16) DEFAULT NULL
        COMMENT '手机呈现真实终态' AFTER `outcome`,
    ADD COLUMN `mobile_terminal_reason` varchar(32) DEFAULT NULL
        COMMENT '手机打断或失败受控原因' AFTER `mobile_terminal_status`;

UPDATE `ai_device_proactive_event`
SET `delivery_group_key` = SHA2(CONCAT(`event_type`, ':',
        CASE WHEN `event_type` IN ('WEATHER_ALERT', 'NEWS_ALERT')
             THEN `dedupe_key` ELSE `event_id` END), 256),
    `delivery_group_window_hours` = CASE
        WHEN `event_type` = 'NEWS_ALERT' THEN 24
        WHEN `event_type` = 'WEATHER_ALERT' AND `dedupe_key` LIKE 'weather-forecast:%' THEN 12
        ELSE 0 END
WHERE `delivery_group_key` IS NULL;

ALTER TABLE `ai_device_proactive_event`
    MODIFY COLUMN `delivery_group_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    ADD KEY `idx_ai_device_proactive_event_delivery_group` (`delivery_group_key`);

CREATE TABLE `ai_proactive_delivery_claim` (
    `user_id` bigint NOT NULL,
    `delivery_group_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `delivery_status` varchar(16) NOT NULL,
    `claim_token` varchar(64) DEFAULT NULL,
    `claimed_at` datetime(3) DEFAULT NULL,
    `device_id` varchar(32) DEFAULT NULL,
    `event_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `event_created_at` datetime(3) DEFAULT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`user_id`, `delivery_group_key`),
    KEY `idx_proactive_delivery_claim_lease` (`delivery_status`, `claimed_at`),
    CONSTRAINT `chk_proactive_delivery_claim_status`
        CHECK (`delivery_status` IN ('PENDING','CLAIMED','DELIVERED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='同一用户跨手机和音箱主动事件原子领取账本';
