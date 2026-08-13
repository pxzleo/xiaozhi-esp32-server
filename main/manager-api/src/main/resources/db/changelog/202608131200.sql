ALTER TABLE `ai_device_proactive_event`
    ADD COLUMN `delivery_mode` varchar(16) NOT NULL DEFAULT 'LEGACY_COMPETE'
        COMMENT '旧事件竞争领取；新事件按终端独立多播' AFTER `delivery_group_window_hours`,
    ADD CONSTRAINT `chk_proactive_event_delivery_mode`
        CHECK (`delivery_mode` IN ('LEGACY_COMPETE','MULTICAST'));

CREATE TABLE `ai_proactive_delivery_route_account` (
    `user_id` bigint NOT NULL,
    `default_device_ids` json NOT NULL,
    `location_authority_mobile_instance_id` varchar(36) DEFAULT NULL,
    `version` int unsigned NOT NULL DEFAULT 1,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_delivery_route_authority_mobile`
        FOREIGN KEY (`location_authority_mobile_instance_id`)
        REFERENCES `ai_mobile_instance` (`mobile_instance_id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号级主动行为投递路由';

CREATE TABLE `ai_proactive_delivery_route_place` (
    `user_id` bigint NOT NULL,
    `place_id` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `place_name` varchar(80) NOT NULL,
    `device_ids` json NOT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`user_id`,`place_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号地点到接收终端映射';

CREATE TABLE `ai_proactive_device_route` (
    `user_id` bigint NOT NULL,
    `device_id` varchar(32) NOT NULL,
    `fixed_place_id` varchar(40) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`user_id`,`device_id`),
    CONSTRAINT `fk_proactive_device_route_device`
        FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE,
    CONSTRAINT `fk_proactive_device_route_place`
        FOREIGN KEY (`user_id`,`fixed_place_id`)
        REFERENCES `ai_proactive_delivery_route_place` (`user_id`,`place_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='终端固定地点';

CREATE TABLE `ai_proactive_user_location` (
    `user_id` bigint NOT NULL,
    `mobile_instance_id` varchar(36) NOT NULL,
    `place_id` varchar(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `transition` varchar(16) NOT NULL,
    `observed_at` datetime(3) NOT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_proactive_location_mobile`
        FOREIGN KEY (`mobile_instance_id`) REFERENCES `ai_mobile_instance` (`mobile_instance_id`) ON DELETE CASCADE,
    CONSTRAINT `chk_proactive_location_transition`
        CHECK (`transition` IN ('entered','exited','dwelled')),
    KEY `idx_proactive_location_fresh` (`mobile_instance_id`,`observed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='权威手机最近地点状态，仅用于投递路由';

CREATE TABLE `ai_proactive_schedule` (
    `id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `user_id` bigint NOT NULL,
    `source_device_id` varchar(32) NOT NULL,
    `source_schedule_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `kind` varchar(16) NOT NULL,
    `label` varchar(120) NOT NULL,
    `recurrence` varchar(16) NOT NULL DEFAULT 'once',
    `weekdays` json NOT NULL,
    `sections` json NOT NULL,
    `location` varchar(40) DEFAULT NULL,
    `scheduled_at` datetime(3) NOT NULL,
    `state` varchar(16) NOT NULL,
    `last_triggered_at` datetime(3) DEFAULT NULL,
    `snoozed_until` datetime(3) DEFAULT NULL,
    `version` int unsigned NOT NULL DEFAULT 1,
    `created_at` datetime(3) NOT NULL,
    `updated_at` datetime(3) NOT NULL,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_proactive_schedule_source` (`source_device_id`,`source_schedule_id`),
    KEY `idx_proactive_schedule_user_time` (`user_id`,`scheduled_at`),
    CONSTRAINT `fk_proactive_schedule_source` FOREIGN KEY (`source_device_id`)
        REFERENCES `ai_device` (`id`) ON DELETE CASCADE,
    CONSTRAINT `chk_proactive_schedule_kind` CHECK (`kind` IN ('alarm','reminder','briefing')),
    CONSTRAINT `chk_proactive_schedule_recurrence`
        CHECK (`recurrence` IN ('once','daily','weekdays','weekends','weekly')),
    CONSTRAINT `chk_proactive_schedule_state`
        CHECK (`state` IN ('scheduled','triggered','stopped','snoozed','completed'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号共享权威闹铃提醒';
