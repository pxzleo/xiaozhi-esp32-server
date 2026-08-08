ALTER TABLE `ai_device_proactive_event`
    ADD COLUMN `claim_token` varchar(64) DEFAULT NULL COMMENT '当前投递租约令牌' AFTER `delivered_at`,
    ADD COLUMN `claimed_at` datetime DEFAULT NULL COMMENT '当前投递租约领取时间' AFTER `claim_token`,
    ADD KEY `idx_ai_device_proactive_event_claim` (`delivery_status`, `claimed_at`);
