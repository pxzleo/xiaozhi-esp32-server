ALTER TABLE `ai_proactive_schedule`
    ADD COLUMN `next_trigger_at` datetime(3) NULL AFTER `scheduled_at`,
    ADD COLUMN `sync_revision` bigint unsigned NOT NULL DEFAULT 0 AFTER `version`,
    ADD COLUMN `deleted_at` datetime(3) NULL AFTER `sync_revision`,
    ADD KEY `idx_proactive_schedule_due` (`state`,`next_trigger_at`),
    ADD KEY `idx_proactive_schedule_user_revision` (`user_id`,`sync_revision`);

-- 不截断或静默删除旧日程；若存在超过设备预算的旧标题，迁移明确失败并要求先修正数据。
ALTER TABLE `ai_proactive_schedule`
    ADD CONSTRAINT `chk_proactive_schedule_label_length`
        CHECK (CHAR_LENGTH(`label`) BETWEEN 1 AND 80);

UPDATE `ai_proactive_schedule`
SET `next_trigger_at`=COALESCE(`snoozed_until`,`scheduled_at`)
WHERE `next_trigger_at` IS NULL AND `state` IN ('scheduled','snoozed');

ALTER TABLE `ai_proactive_schedule`
    DROP CHECK `chk_proactive_schedule_state`,
    ADD CONSTRAINT `chk_proactive_schedule_state`
        CHECK (`state` IN ('scheduled','triggered','stopped','snoozed','completed','deleted'));

CREATE TABLE `ai_proactive_schedule_revision` (
    `revision` bigint unsigned NOT NULL AUTO_INCREMENT,
    `user_id` bigint NOT NULL,
    `schedule_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`revision`),
    KEY `idx_schedule_revision_user` (`user_id`,`revision`),
    CONSTRAINT `fk_schedule_revision_schedule` FOREIGN KEY (`schedule_id`)
        REFERENCES `ai_proactive_schedule` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权威日程增量游标';

INSERT INTO `ai_proactive_schedule_revision` (`user_id`,`schedule_id`,`created_at`)
SELECT `user_id`,`id`,CURRENT_TIMESTAMP(3) FROM `ai_proactive_schedule`;

UPDATE `ai_proactive_schedule` s
INNER JOIN `ai_proactive_schedule_revision` r ON r.schedule_id=s.id
SET s.sync_revision=r.revision;

CREATE TABLE `ai_proactive_schedule_action` (
    `revision` bigint unsigned NOT NULL AUTO_INCREMENT,
    `action_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `user_id` bigint NOT NULL,
    `schedule_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `source_device_id` varchar(32) NOT NULL,
    `source_schedule_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `schedule_version` int unsigned NOT NULL,
    `action` varchar(16) NOT NULL,
    `snoozed_until` datetime(3) NULL,
    `next_trigger_at` datetime(3) NULL,
    `created_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`revision`),
    UNIQUE KEY `uk_schedule_action_id` (`action_id`),
    KEY `idx_schedule_action_user` (`user_id`,`revision`),
    CONSTRAINT `fk_schedule_action_schedule` FOREIGN KEY (`schedule_id`)
        REFERENCES `ai_proactive_schedule` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_schedule_action_source` FOREIGN KEY (`source_device_id`)
        REFERENCES `ai_device` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_schedule_action` CHECK (`action` IN ('stop','snooze','complete','delete'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='音箱反向日程动作';

CREATE TABLE `ai_proactive_schedule_device_cursor` (
    `device_id` varchar(32) NOT NULL,
    `acked_action_revision` bigint unsigned NOT NULL DEFAULT 0,
    `updated_at` datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (`device_id`),
    CONSTRAINT `fk_schedule_cursor_device` FOREIGN KEY (`device_id`)
        REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='音箱日程动作确认游标';
