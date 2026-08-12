-- 地点变化改为仅审计。先按用户冻结地点主动事件组及真实投递事实，后续更新不再
-- 依赖正在修改的主动事件表，避免跨租户同键扩散和 MySQL 1093 自引用限制。
DROP TEMPORARY TABLE IF EXISTS `tmp_location_audit_groups`;

CREATE TEMPORARY TABLE `tmp_location_audit_groups` (
    `user_id` bigint NOT NULL,
    `delivery_group_key` char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `has_delivered` tinyint(1) NOT NULL DEFAULT 0,
    PRIMARY KEY (`user_id`, `delivery_group_key`)
) ENGINE=InnoDB;

INSERT IGNORE INTO `tmp_location_audit_groups` (`user_id`, `delivery_group_key`)
SELECT mobile_device.user_id, source.delivery_group_key
FROM `ai_mobile_event` me
INNER JOIN `ai_mobile_instance` mi
    ON mi.mobile_instance_id = me.mobile_instance_id
INNER JOIN `ai_device` mobile_device
    ON mobile_device.id = mi.device_id
   AND mobile_device.user_id = mi.user_id
INNER JOIN `ai_device_proactive_event` source
    ON source.device_id = mi.device_id
   AND source.event_id = me.proactive_event_id
   AND source.event_type = 'MOBILE_ALERT'
WHERE me.event_type = 'location.transition';

UPDATE `tmp_location_audit_groups` location_group
INNER JOIN `ai_device` delivered_device
    ON delivered_device.user_id = location_group.user_id
INNER JOIN `ai_device_proactive_event` delivered
    ON delivered.device_id = delivered_device.id
   AND delivered.delivery_group_key = location_group.delivery_group_key
   AND delivered.delivery_status = 'DELIVERED'
SET location_group.has_delivered = 1;

UPDATE `tmp_location_audit_groups` location_group
INNER JOIN `ai_proactive_delivery_claim` delivered_claim
    ON delivered_claim.user_id = location_group.user_id
   AND delivered_claim.delivery_group_key = location_group.delivery_group_key
   AND delivered_claim.delivery_status = 'DELIVERED'
SET location_group.has_delivered = 1;

-- 把任一同用户副本的真实投递固化到共享账本。这样其余未投递副本终结后，
-- 手机事件审计仍能从同一用户、同一组读到 DELIVERED，而不会依赖某个手机副本。
INSERT INTO `ai_proactive_delivery_claim` (
    `user_id`, `delivery_group_key`, `delivery_status`, `claim_token`, `claimed_at`,
    `device_id`, `event_id`, `event_created_at`, `updated_at`
)
SELECT location_group.user_id, location_group.delivery_group_key, 'DELIVERED', NULL, NULL,
       NULL, NULL, MIN(delivered.created_at),
       CURRENT_TIMESTAMP(3)
FROM `tmp_location_audit_groups` location_group
INNER JOIN `ai_device` delivered_device
    ON delivered_device.user_id = location_group.user_id
INNER JOIN `ai_device_proactive_event` delivered
    ON delivered.device_id = delivered_device.id
   AND delivered.delivery_group_key = location_group.delivery_group_key
   AND delivered.delivery_status = 'DELIVERED'
WHERE location_group.has_delivered = 1
GROUP BY location_group.user_id, location_group.delivery_group_key
ON DUPLICATE KEY UPDATE
    delivery_status = 'DELIVERED',
    claim_token = NULL,
    claimed_at = NULL,
    updated_at = CURRENT_TIMESTAMP(3);

UPDATE `ai_device_proactive_event` copies
INNER JOIN `ai_device` copy_device
    ON copy_device.id = copies.device_id
INNER JOIN `tmp_location_audit_groups` location_group
    ON location_group.user_id = copy_device.user_id
   AND location_group.delivery_group_key = copies.delivery_group_key
SET copies.delivery_status = 'DISMISSED',
    copies.outcome = 'DISMISSED',
    copies.claim_token = NULL,
    copies.claimed_at = NULL,
    copies.updated_at = CURRENT_TIMESTAMP(3)
WHERE copies.event_type = 'MOBILE_ALERT'
  AND copies.delivery_status IN ('PENDING', 'CLAIMED', 'FAILED');

-- 尚未转成主动事件的地点记录可直接终结为只审计。
UPDATE `ai_mobile_event`
SET processing_status = 'ignored',
    reason_code = 'location_audit_only',
    category = NULL,
    severity = NULL,
    confidence = NULL,
    spoken_summary = NULL,
    proactive_event_id = NULL,
    processing_lease_owner = NULL,
    processing_lease_token = NULL,
    processing_lease_until = NULL,
    next_attempt_at = NULL,
    processed_at = CURRENT_TIMESTAMP(3),
    updated_at = CURRENT_TIMESTAMP(3)
WHERE event_type = 'location.transition'
  AND processing_status IN ('received', 'error');

-- 释放未投递地点组遗留的共享领取租约；FAILED 不会使已 DISMISSED 副本重新可见。
UPDATE `ai_proactive_delivery_claim` dc
INNER JOIN `tmp_location_audit_groups` location_group
    ON location_group.user_id = dc.user_id
   AND location_group.delivery_group_key = dc.delivery_group_key
   AND location_group.has_delivered = 0
SET dc.delivery_status = 'FAILED',
    dc.claim_token = NULL,
    dc.claimed_at = NULL,
    dc.updated_at = CURRENT_TIMESTAMP(3)
WHERE dc.delivery_status = 'CLAIMED';

-- 已转成主动事件但从未真实投递的记录解除关联；已投递组保留原关联和审计事实。
UPDATE `ai_mobile_event` me
INNER JOIN `ai_mobile_instance` mi
    ON mi.mobile_instance_id = me.mobile_instance_id
INNER JOIN `ai_device` mobile_device
    ON mobile_device.id = mi.device_id
   AND mobile_device.user_id = mi.user_id
INNER JOIN `ai_device_proactive_event` source
    ON source.device_id = mi.device_id
   AND source.event_id = me.proactive_event_id
   AND source.event_type = 'MOBILE_ALERT'
INNER JOIN `tmp_location_audit_groups` location_group
    ON location_group.user_id = mobile_device.user_id
   AND location_group.delivery_group_key = source.delivery_group_key
   AND location_group.has_delivered = 0
SET me.processing_status = 'ignored',
    me.reason_code = 'location_audit_only',
    me.category = NULL,
    me.severity = NULL,
    me.confidence = NULL,
    me.spoken_summary = NULL,
    me.proactive_event_id = NULL,
    me.processing_lease_owner = NULL,
    me.processing_lease_token = NULL,
    me.processing_lease_until = NULL,
    me.next_attempt_at = NULL,
    me.processed_at = CURRENT_TIMESTAMP(3),
    me.updated_at = CURRENT_TIMESTAMP(3)
WHERE me.event_type = 'location.transition'
  AND me.processing_status = 'converted';

DROP TEMPORARY TABLE `tmp_location_audit_groups`;
