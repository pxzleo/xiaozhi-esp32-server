ALTER TABLE `ai_device`
  ADD COLUMN `display_name` VARCHAR(64) NULL COMMENT '用户自定义显示名称' AFTER `alias`;

UPDATE `ai_device`
SET `display_name`=CASE
  WHEN NULLIF(TRIM(`alias`),'') IS NOT NULL
    AND LOWER(REPLACE(REPLACE(TRIM(`alias`),':',''),'-',''))<>
        LOWER(REPLACE(REPLACE(TRIM(`mac_address`),':',''),'-','')) THEN TRIM(`alias`)
  ELSE NULL END
WHERE `display_name` IS NULL;

CREATE TABLE `ai_proactive_place_catalog` (
  `user_id` BIGINT NOT NULL,
  `place_id` VARCHAR(40) NOT NULL,
  `place_name` VARCHAR(80) NOT NULL,
  `source_mobile_instance_id` VARCHAR(36) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`user_id`,`place_id`),
  KEY `idx_proactive_place_source` (`source_mobile_instance_id`),
  CONSTRAINT `fk_proactive_place_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_proactive_place_mobile` FOREIGN KEY (`source_mobile_instance_id`)
    REFERENCES `ai_mobile_instance` (`mobile_instance_id`) ON DELETE CASCADE,
  CONSTRAINT `chk_proactive_place_id` CHECK (`place_id` REGEXP '^place_[0-9a-f]{8,32}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='账号地点目录';

INSERT INTO `ai_proactive_place_catalog`
  (`user_id`,`place_id`,`place_name`,`source_mobile_instance_id`,`updated_at`)
SELECT p.user_id,p.place_id,p.place_name,a.location_authority_mobile_instance_id,p.updated_at
FROM `ai_proactive_delivery_route_place` p
INNER JOIN `ai_proactive_delivery_route_account` a ON a.user_id=p.user_id
WHERE a.location_authority_mobile_instance_id IS NOT NULL
ON DUPLICATE KEY UPDATE place_name=VALUES(place_name),updated_at=VALUES(updated_at);
