UPDATE ai_device_proactive_monitor
SET config = JSON_SET(
        JSON_REMOVE(config, '$.official_min_severity', '$.temperatureRangeValid'),
        '$.minimum_warning_severity',
        COALESCE(
            JSON_UNQUOTE(JSON_EXTRACT(config, '$.minimum_warning_severity')),
            CASE JSON_UNQUOTE(JSON_EXTRACT(config, '$.official_min_severity'))
                WHEN 'advisory' THEN 'minor'
                WHEN 'watch' THEN 'moderate'
                WHEN 'warning' THEN
                    CASE
                        WHEN JSON_LENGTH(config) IN (10, 11)
                         AND JSON_CONTAINS(config, CAST(
                            '{"source":"agent_plugin","hazard_types":[],"official_min_severity":"warning","precip_probability":70,"wind_speed_kmh":62,"high_temp_c":35,"low_temp_c":0,"temp_drop_24h_c":8,"forecast_hours":6,"cooldown_minutes":720}'
                            AS JSON)) = 1
                         AND (JSON_LENGTH(config) = 10
                              OR JSON_EXTRACT(config, '$.temperatureRangeValid') = true)
                        THEN 'moderate'
                        ELSE 'severe'
                    END
                WHEN 'emergency' THEN 'extreme'
                ELSE 'moderate'
            END)),
    version = version + 1,
    updated_at = CURRENT_TIMESTAMP
WHERE monitor_type = 'WEATHER'
  AND (JSON_CONTAINS_PATH(config, 'one', '$.official_min_severity') = 1
       OR JSON_CONTAINS_PATH(config, 'one', '$.minimum_warning_severity') = 0);
