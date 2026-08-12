ALTER TABLE `ai_mobile_instance`
    ADD COLUMN `alert_sensitivity` varchar(16) NOT NULL DEFAULT 'balanced' AFTER `capabilities`,
    ADD COLUMN `alert_categories` varchar(160) NOT NULL
        DEFAULT 'security,call,parcel,appointment,message,other' AFTER `alert_sensitivity`;
