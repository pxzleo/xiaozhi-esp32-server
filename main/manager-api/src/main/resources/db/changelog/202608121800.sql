INSERT INTO ai_proactive_delivery_claim
    (user_id, delivery_group_key, delivery_status, claim_token, claimed_at,
     device_id, event_id, event_created_at, updated_at)
SELECT d.user_id, delivered.delivery_group_key, 'DELIVERED', NULL, NULL,
       delivered.device_id, delivered.event_id, delivered.created_at, CURRENT_TIMESTAMP(3)
FROM ai_device_proactive_event delivered
INNER JOIN ai_device d ON d.id=delivered.device_id
INNER JOIN (
  SELECT d2.user_id, e2.delivery_group_key, MAX(e2.id) AS delivered_id
  FROM ai_device_proactive_event e2
  INNER JOIN ai_device d2 ON d2.id=e2.device_id
  WHERE e2.delivery_status='DELIVERED' AND e2.delivery_group_key IS NOT NULL
  GROUP BY d2.user_id, e2.delivery_group_key
) chosen ON chosen.user_id=d.user_id AND chosen.delivered_id=delivered.id
ON DUPLICATE KEY UPDATE delivery_group_key=VALUES(delivery_group_key);

UPDATE ai_device_proactive_event e
INNER JOIN ai_device d ON d.id=e.device_id
INNER JOIN ai_proactive_delivery_claim dc
  ON dc.user_id=d.user_id
 AND dc.delivery_group_key=e.delivery_group_key
 AND dc.delivery_status='DELIVERED'
SET e.delivery_status='DISMISSED',
    e.outcome='DISMISSED',
    e.claim_token=NULL,
    e.claimed_at=NULL,
    e.updated_at=CURRENT_TIMESTAMP(3)
WHERE e.delivery_status IN ('PENDING','CLAIMED')
  AND (e.delivery_group_window_hours=0
       OR (dc.event_created_at IS NOT NULL
           AND e.created_at < DATE_ADD(dc.event_created_at,
               INTERVAL e.delivery_group_window_hours HOUR)));
