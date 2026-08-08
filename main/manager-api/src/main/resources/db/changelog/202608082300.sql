UPDATE ai_device_proactive_monitor
SET config = JSON_SET(
        JSON_REMOVE(config, '$.official_min_severity'),
        '$.minimum_warning_severity',
        COALESCE(
            JSON_UNQUOTE(JSON_EXTRACT(config, '$.minimum_warning_severity')),
            CASE JSON_UNQUOTE(JSON_EXTRACT(config, '$.official_min_severity'))
                WHEN 'advisory' THEN 'minor'
                WHEN 'watch' THEN 'moderate'
                WHEN 'warning' THEN 'severe'
                WHEN 'emergency' THEN 'extreme'
                ELSE 'moderate'
            END)),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE monitor_type = 'WEATHER'
  AND (JSON_CONTAINS_PATH(config, 'one', '$.official_min_severity') = 1
       OR JSON_CONTAINS_PATH(config, 'one', '$.minimum_warning_severity') = 0);
