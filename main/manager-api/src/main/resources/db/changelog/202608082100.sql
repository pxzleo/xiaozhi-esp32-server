CREATE TABLE `ai_device_proactive_monitor` (
    `device_id` varchar(32) NOT NULL COMMENT 'ai_device.id',
    `mac_address` varchar(50) DEFAULT NULL COMMENT '设备MAC地址',
    `monitor_type` varchar(16) NOT NULL COMMENT 'WEATHER或NEWS',
    `enabled` tinyint(1) NOT NULL DEFAULT 1,
    `interval_minutes` int unsigned NOT NULL,
    `config` json NOT NULL,
    `state` json NOT NULL,
    `last_success_at` datetime DEFAULT NULL,
    `next_check_at` datetime NOT NULL,
    `last_error_code` varchar(64) DEFAULT NULL,
    `last_probe_at` datetime DEFAULT NULL,
    `lease_owner` varchar(64) DEFAULT NULL,
    `lease_token` varchar(64) DEFAULT NULL,
    `lease_until` datetime DEFAULT NULL,
    `version` int unsigned NOT NULL DEFAULT 0,
    `created_at` datetime NOT NULL,
    `updated_at` datetime NOT NULL,
    PRIMARY KEY (`device_id`, `monitor_type`),
    CONSTRAINT `chk_proactive_monitor_type` CHECK (`monitor_type` IN ('WEATHER', 'NEWS')),
    CONSTRAINT `chk_proactive_monitor_interval` CHECK (`interval_minutes` BETWEEN 5 AND 1440),
    KEY `idx_proactive_monitor_due` (`enabled`, `next_check_at`, `lease_until`),
    KEY `idx_proactive_monitor_probe` (`last_probe_at`),
    CONSTRAINT `fk_proactive_monitor_device` FOREIGN KEY (`device_id`) REFERENCES `ai_device` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='设备外界主动监测配置与状态';

INSERT INTO `ai_device_proactive_monitor`
    (`device_id`, `mac_address`, `monitor_type`, `enabled`, `interval_minutes`, `config`, `state`,
     `next_check_at`, `version`, `created_at`, `updated_at`)
SELECT d.id, d.mac_address, t.monitor_type, 1,
       CASE t.monitor_type WHEN 'WEATHER' THEN 30 ELSE 10 END,
       CASE t.monitor_type
         WHEN 'WEATHER' THEN JSON_OBJECT('source','agent_plugin','hazard_types',JSON_ARRAY(),
           'official_min_severity','warning','precip_probability',70,'wind_speed_kmh',62,
           'high_temp_c',35,'low_temp_c',0,'temp_drop_24h_c',8,'forecast_hours',6,'cooldown_minutes',720)
         ELSE JSON_OBJECT('source_mode','agent_plugin','sources',JSON_ARRAY(),'categories',JSON_ARRAY(),
           'confidence',0.85,'cooldown_minutes',120,'dedupe_hours',24,'scope','domestic_and_international')
       END,
       JSON_OBJECT(), NOW(), 0, NOW(), NOW()
FROM `ai_device` d
CROSS JOIN (SELECT 'WEATHER' monitor_type UNION ALL SELECT 'NEWS') t;

INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT ids.next_id, 'proactive.classifier.model_id', '', 'string', 0, '外界新闻分类专用LLM模型ID'
FROM (SELECT COALESCE(MAX(`id`), 0) + 1 AS next_id FROM `sys_params`) ids
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'proactive.classifier.model_id'
);
