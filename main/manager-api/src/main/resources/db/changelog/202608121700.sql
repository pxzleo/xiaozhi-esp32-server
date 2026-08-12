ALTER TABLE `ai_mobile_event`
    ADD KEY `idx_mobile_event_audit_time`
        (`mobile_instance_id`, `occurred_at`, `event_id`);
