ALTER TABLE `ai_mobile_instance`
    ADD COLUMN `stable_device_key` char(64) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL
        COMMENT 'Android按应用隔离设备标识的SHA-256，不保存原标识' AFTER `installation_id`,
    ADD COLUMN `canonical_instance_id` varchar(36) DEFAULT NULL
        COMMENT '同一物理手机合并后的权威实例' AFTER `stable_device_key`;

UPDATE `ai_mobile_instance`
SET `canonical_instance_id` = `mobile_instance_id`
WHERE `canonical_instance_id` IS NULL;

ALTER TABLE `ai_mobile_instance`
    MODIFY COLUMN `canonical_instance_id` varchar(36) NOT NULL,
    ADD UNIQUE KEY `uk_mobile_instance_owner_stable_device` (`user_id`, `stable_device_key`),
    ADD KEY `idx_mobile_instance_canonical` (`canonical_instance_id`);
