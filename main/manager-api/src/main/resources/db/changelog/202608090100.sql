INSERT INTO `sys_params` (`id`, `param_code`, `param_value`, `value_type`, `param_type`, `remark`)
SELECT ids.next_id, 'proactive.external_monitoring_enabled', 'false', 'boolean', 0,
       '是否全局启用天气与新闻外界监测'
FROM (SELECT COALESCE(MAX(`id`), 0) + 1 AS next_id FROM `sys_params`) ids
WHERE NOT EXISTS (
    SELECT 1 FROM `sys_params` WHERE `param_code` = 'proactive.external_monitoring_enabled'
);
