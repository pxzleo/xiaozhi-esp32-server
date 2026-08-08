package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.device.proactive.ProactiveDTOs.MonitorsUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ExternalMonitoringUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.NewsMonitorConfig;
import xiaozhi.modules.device.proactive.ProactiveDTOs.WeatherMonitorConfig;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.NewsCategory;
import xiaozhi.modules.device.proactive.ProactiveEnums.NewsSeverity;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveEnums.WeatherHazardType;
import xiaozhi.modules.device.proactive.ProactiveEnums.WeatherWarningSeverity;
import xiaozhi.modules.security.config.ShiroConfig;
import xiaozhi.modules.device.controller.ProactiveSettingsController;
import xiaozhi.modules.config.controller.ProactiveConfigController;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;

class ProactiveMonitorContractTest {
    @Test
    void externalMonitoringMigrationDefaultsFalseAndIsOrderedAdditively() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608090100.sql"));
        assertTrue(sql.contains("'proactive.external_monitoring_enabled', 'false', 'boolean', 0"));
        assertTrue(sql.contains("WHERE NOT EXISTS"));
        assertFalse(sql.toLowerCase().contains("update ai_device_proactive_monitor"));
        String master = Files.readString(
                Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));
        assertTrue(master.indexOf("202608090100") > master.indexOf("202608082300"));
        assertTrue(master.indexOf("202608090130") > master.indexOf("202608090100"));
    }

    @Test
    void externalMonitoringSettingsAreStrictAndSuperAdminOnly() throws Exception {
        RequiresPermissions permission = ProactiveSettingsController.class
                .getAnnotation(RequiresPermissions.class);
        assertEquals(Set.of("sys:role:superAdmin"), Set.of(permission.value()));
        RequestMapping root = ProactiveSettingsController.class.getAnnotation(RequestMapping.class);
        assertEquals("/proactive/settings", root.value()[0]);
        Method get = ProactiveSettingsController.class.getMethod("externalMonitoring");
        Method put = ProactiveSettingsController.class.getMethod(
                "saveExternalMonitoring", ExternalMonitoringUpdate.class);
        assertEquals("/external-monitoring", get.getAnnotation(GetMapping.class).value()[0]);
        assertEquals("/external-monitoring", put.getAnnotation(PutMapping.class).value()[0]);

        ObjectMapper mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"enabled\":true,\"unknown\":1}", ExternalMonitoringUpdate.class));
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(
                    mapper.readValue("{}", ExternalMonitoringUpdate.class)).isEmpty());
        }
    }

    @Test
    void monitorEventCreateEndpointReturnsAuthoritativeDedupeResultContract() throws Exception {
        Method create = ProactiveConfigController.class.getMethod("monitorEvent", EventUpsert.class);
        assertEquals("/monitor-events",
                create.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value()[0]);
        Set<String> fields = Arrays.stream(ProactiveDTOs.EventCreateResult.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("created", "deduped", "authoritativeEventId", "event"), fields);
    }

    @Test
    void enumWireValuesAndTopicCapacityAreExtendedCompatibly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("news", mapper.writeValueAsString(Topic.NEWS).replace("\"", ""));
        assertEquals("news_alert", mapper.writeValueAsString(EventType.NEWS_ALERT).replace("\"", ""));
        assertEquals(8, Topic.values().length);
    }

    @Test
    void strictMonitorJsonRejectsUnknownFields() {
        String json = """
                {"weather":{"enabled":true,"interval_minutes":30,"config":{
                "source":"agent_plugin","hazard_types":[],"minimum_warning_severity":"moderate",
                "precip_probability":70,"wind_speed_kmh":62,"high_temp_c":35,"low_temp_c":0,
                "temp_drop_24h_c":8,"forecast_hours":6,"cooldown_minutes":720,"unknown":1}},
                "news":{"enabled":true,"interval_minutes":10,"config":{"source_mode":"agent_plugin",
                "sources":[],"categories":[],"confidence":0.85,"cooldown_minutes":120,
                "dedupe_hours":24,"scope":"domestic_and_international"}}}
                """;
        assertThrows(Exception.class, () -> new ObjectMapper().readValue(json, MonitorsUpdate.class));
    }

    @Test
    void monitorHazardsAndNewsCategoriesUseClosedWireEnums() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(Set.of("rainstorm", "thunderstorm", "hail", "blizzard", "high_wind",
                "high_temperature", "low_temperature", "temperature_drop"),
                Arrays.stream(WeatherHazardType.values()).map(WeatherHazardType::wireValue)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("public_safety", "natural_disaster", "major_policy",
                "international_conflict", "major_economy", "major_technology"),
                Arrays.stream(NewsCategory.values()).map(NewsCategory::wireValue)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of("low", "medium", "high", "critical"),
                Arrays.stream(NewsSeverity.values()).map(NewsSeverity::wireValue)
                        .collect(java.util.stream.Collectors.toSet()));
        String valid = """
                {"weather":{"enabled":true,"interval_minutes":30,"config":{
                "source":"agent_plugin","hazard_types":["rainstorm","temperature_drop"],
                "minimum_warning_severity":"moderate","precip_probability":70,"wind_speed_kmh":62,
                "high_temp_c":35,"low_temp_c":0,"temp_drop_24h_c":8,"forecast_hours":6,
                "cooldown_minutes":720}},"news":{"enabled":true,"interval_minutes":10,"config":{
                "source_mode":"agent_plugin","sources":[],"categories":["public_safety","major_technology"],
                "confidence":0.85,"cooldown_minutes":120,"dedupe_hours":24,
                "scope":"domestic_and_international"}}}
                """;
        MonitorsUpdate parsed = mapper.readValue(valid, MonitorsUpdate.class);
        assertEquals("rainstorm", parsed.getWeather().getConfig().getHazardTypes().getFirst().wireValue());
        assertEquals("public_safety", parsed.getNews().getConfig().getCategories().getFirst().wireValue());
        assertThrows(Exception.class, () -> mapper.readValue(
                valid.replace("rainstorm", "typhoon"), MonitorsUpdate.class));
        assertThrows(Exception.class, () -> mapper.readValue(
                valid.replace("public_safety", "celebrity_gossip"), MonitorsUpdate.class));
    }

    @Test
    void weatherWarningSeverityUsesStrictQWeatherOfficialValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(WeatherWarningSeverity.MODERATE,
                new WeatherMonitorConfig().getMinimumWarningSeverity());
        assertEquals(Set.of("minor", "moderate", "severe", "extreme"),
                Arrays.stream(WeatherWarningSeverity.values())
                        .map(WeatherWarningSeverity::wireValue)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(WeatherWarningSeverity.SEVERE, mapper.readValue(
                "{\"minimum_warning_severity\":\"severe\"}", WeatherMonitorConfig.class)
                .getMinimumWarningSeverity());
        String serialized = mapper.writeValueAsString(new WeatherMonitorConfig());
        assertTrue(serialized.contains("\"minimum_warning_severity\":\"moderate\""));
        assertFalse(serialized.contains("official_min_severity"));
        assertFalse(serialized.contains("temperatureRangeValid"));
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"minimum_warning_severity\":\"warning\"}", WeatherMonitorConfig.class));
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"official_min_severity\":\"warning\"}", WeatherMonitorConfig.class));
        WeatherMonitorConfig nullSeverity = mapper.readValue(
                "{\"minimum_warning_severity\":null}", WeatherMonitorConfig.class);
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(nullSeverity).stream()
                    .anyMatch(violation -> "minimumWarningSeverity"
                            .equals(violation.getPropertyPath().toString())));
        }
    }

    @Test
    void monitorNumericRangesAreExplicitlyValidated() {
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            WeatherMonitorConfig weather = new WeatherMonitorConfig();
            weather.setPrecipProbability(101);
            weather.setForecastHours(0);
            NewsMonitorConfig news = new NewsMonitorConfig();
            news.setConfidence(0.49);
            news.setDedupeHours(0);
            assertEquals(2, validator.validate(weather).size());
            assertEquals(2, validator.validate(news).size());
        }
    }

    @Test
    void explicitNullWeatherNumbersAreRejectedInsteadOfBecomingZero() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String base = """
                {"source":"agent_plugin","hazard_types":[],"minimum_warning_severity":"moderate",
                "precip_probability":70,"wind_speed_kmh":62,"high_temp_c":35,"low_temp_c":0,
                "temp_drop_24h_c":8,"forecast_hours":6,"cooldown_minutes":720}
                """;
        try (var factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String field : java.util.List.of("precip_probability", "wind_speed_kmh",
                    "high_temp_c", "low_temp_c", "temp_drop_24h_c", "forecast_hours",
                    "cooldown_minutes")) {
                String json = base.replace("\"" + field + "\":"
                        + valueOf(base, field), "\"" + field + "\":null");
                WeatherMonitorConfig config = mapper.readValue(json, WeatherMonitorConfig.class);
                assertTrue(validator.validate(config).stream()
                        .anyMatch(v -> v.getPropertyPath().toString().equals(fieldToProperty(field))), field);
            }
        }
    }

    @Test
    void migrationCreatesCompositeKeyCascadeEmptyBaselineAndTwoDefaults() throws Exception {
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608082100.sql"));
        assertTrue(sql.contains("PRIMARY KEY (`device_id`, `monitor_type`)"));
        assertTrue(sql.contains("ON DELETE CASCADE"));
        assertTrue(sql.contains("JSON_OBJECT(), NOW(), 0"));
        assertTrue(sql.contains("SELECT 'WEATHER' monitor_type UNION ALL SELECT 'NEWS'"));
        assertTrue(sql.contains("'proactive.classifier.model_id'"));
        String severityMigration = Files.readString(Path.of("src/main/resources/db/changelog/202608082300.sql"));
        assertTrue(severityMigration.contains(
                "JSON_REMOVE(config, '$.official_min_severity', '$.temperatureRangeValid')"));
        assertTrue(severityMigration.contains("'$.minimum_warning_severity'"));
        assertTrue(severityMigration.contains("WHEN 'advisory' THEN 'minor'"));
        assertTrue(severityMigration.contains("WHEN 'watch' THEN 'moderate'"));
        assertTrue(severityMigration.contains("WHEN 'warning' THEN"));
        assertTrue(severityMigration.contains("WHEN JSON_LENGTH(config) IN (10, 11)"));
        assertTrue(severityMigration.contains(
                "JSON_EXTRACT(config, '$.temperatureRangeValid') = true"));
        assertTrue(severityMigration.contains("JSON_CONTAINS(config, CAST("));
        assertTrue(severityMigration.contains("\"official_min_severity\":\"warning\""));
        assertTrue(severityMigration.contains("THEN 'moderate'\n                        ELSE 'severe'"));
        assertTrue(severityMigration.contains("WHEN 'emergency' THEN 'extreme'"));
        assertTrue(severityMigration.contains("ELSE 'moderate'"));
        String master = Files.readString(Path.of("src/main/resources/db/changelog/db.changelog-master.yaml"));
        assertTrue(master.contains("classpath:db/changelog/202608082300.sql"));
        assertTrue(master.indexOf("classpath:db/changelog/202608082100.sql")
                < master.indexOf("classpath:db/changelog/202608082300.sql"));
        assertTrue(sql.contains("'official_min_severity','warning'"));
        assertEquals(WeatherWarningSeverity.MODERATE,
                new WeatherMonitorConfig().getMinimumWarningSeverity());
    }

    @Test
    void leaseSqlContractUsesDatabaseClockAndCasPredicates() throws Exception {
        Method probe = ProactiveMonitorDao.class.getMethod("probeAndRebaselineIfOffline", String.class);
        String probeSql = probe.getAnnotation(Update.class).value()[0];
        assertTrue(probeSql.contains("last_probe_at = CURRENT_TIMESTAMP"));
        assertTrue(probeSql.contains("updated_at = CURRENT_TIMESTAMP"));
        assertTrue(probeSql.contains("last_probe_at IS NULL"));
        assertTrue(probeSql.contains("last_probe_at <= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)"));
        assertTrue(probeSql.contains("THEN JSON_OBJECT() ELSE state END"));
        assertTrue(probeSql.contains("THEN CURRENT_TIMESTAMP ELSE next_check_at END"));
        assertTrue(probeSql.contains("THEN NULL ELSE lease_token END"));
        assertTrue(probeSql.contains("WHERE device_id = #{deviceId}"));
        assertFalse(probeSql.contains("ai_device_proactive_event"));
        assertFalse(probeSql.contains("monitor_type ="));
        assertFalse(probeSql.contains("#{now}"));

        Method candidates = ProactiveMonitorDao.class.getMethod("selectDueCandidates", int.class);
        String select = candidates.getAnnotation(Select.class).value()[0];
        assertTrue(select.contains("proactive.external_monitoring_enabled"));
        assertTrue(select.contains("LOWER(TRIM(g.param_value)) = 'true'"));
        assertTrue(select.contains("last_probe_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)"));
        assertTrue(select.contains("LIMIT #{limit}"));
        assertTrue(select.contains("lease_until IS NULL OR m.lease_until <= CURRENT_TIMESTAMP"));
        assertFalse(select.contains("#{now}"));
        assertFalse(select.contains("#{activeCutoff}"));

        Method claim = ProactiveMonitorDao.class.getMethod("claimCas", String.class, String.class,
                String.class, String.class);
        String claimSql = claim.getAnnotation(Update.class).value()[0];
        assertTrue(claimSql.contains("INNER JOIN sys_params g"));
        assertTrue(claimSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(claimSql.contains("m.lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 120 SECOND)"));
        assertTrue(claimSql.contains("m.lease_until IS NULL OR m.lease_until <= CURRENT_TIMESTAMP"));
        assertTrue(claimSql.contains("m.last_probe_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)"));
        assertTrue(claimSql.contains("m.updated_at = CURRENT_TIMESTAMP"));
        assertFalse(claimSql.contains("#{leaseUntil}"));
        assertFalse(claimSql.contains("#{now}"));

        Method complete = ProactiveMonitorDao.class.getMethod("completeCas", String.class, String.class,
                String.class, String.class, boolean.class, String.class, String.class);
        String completeSql = complete.getAnnotation(Update.class).value()[0];
        assertTrue(completeSql.contains("lease_owner = #{leaseOwner} AND lease_token = #{leaseToken}"));
        assertTrue(completeSql.contains("lease_until > CURRENT_TIMESTAMP"));
        assertTrue(completeSql.contains("next_check_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL interval_minutes MINUTE)"));
        assertTrue(completeSql.contains("updated_at = CURRENT_TIMESTAMP"));
        assertFalse(completeSql.contains("#{now}"));

        Method configure = ProactiveMonitorDao.class.getMethod("updateConfiguration", String.class,
                String.class, boolean.class, int.class, String.class, java.util.Date.class);
        String configureSql = configure.getAnnotation(Update.class).value()[0];
        assertTrue(configureSql.contains("lease_owner = NULL"));
        assertTrue(configureSql.contains("lease_token = NULL"));
        assertTrue(configureSql.contains("lease_until = NULL"));
    }

    @Test
    void pendingQueryOnlySelectsMonitorEventsAndNoPayloadProjectionContractLeaks() throws Exception {
        Method pending = ProactiveEventDao.class.getMethod("selectPendingMonitorEvents",
                String.class, java.util.Date.class, java.util.Date.class);
        String sql = pending.getAnnotation(Select.class).value()[0];
        assertTrue(sql.contains("INNER JOIN sys_params g"));
        assertTrue(sql.contains("proactive.external_monitoring_enabled"));
        assertTrue(sql.contains("LOWER(TRIM(g.param_value)) = 'true'"));
        assertTrue(sql.contains("event_type IN ('WEATHER_ALERT', 'NEWS_ALERT')"));
        assertTrue(sql.contains("delivery_status = 'PENDING'"));
        assertTrue(sql.contains("claimed_at < #{claimCutoff}"));
        assertFalse(java.util.Arrays.stream(ProactiveDTOs.PendingEnvelope.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("payload") || component.getName().equals("reason")));
        assertTrue(Arrays.stream(ProactiveDTOs.MonitorsView.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("externalMonitoringEnabled")));
    }

    @Test
    void monitorTaskExposesServerOnlyResolvedWorkerInputsAndUserViewHasNoCredentials() {
        Set<String> fields = Arrays.stream(ProactiveDTOs.MonitorTask.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(fields.containsAll(Set.of("weatherLocation", "weatherLocationError",
                "weatherApiHost", "weatherAuthType", "weatherCredential", "weatherCredentialsError",
                "newsSources", "newsSourcesError")));
        assertFalse(fields.contains("apiKey"));
        assertFalse(fields.contains("provider"));
        assertFalse(fields.contains("pluginConfig"));
        Set<String> userFields = Arrays.stream(ProactiveDTOs.MonitorsView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertFalse(userFields.contains("weatherApiHost"));
        assertFalse(userFields.contains("weatherAuthType"));
        assertFalse(userFields.contains("weatherCredential"));
        assertFalse(userFields.contains("weatherCredentialsError"));
    }

    @Test
    void proactivePluginQueryOnlyLoadsWeatherAndNewsNowMappings() throws Exception {
        String mapper = Files.readString(
                Path.of("src/main/resources/mapper/agent/AgentPluginMappingMapper.xml"));
        String marker = "<select id=\"selectProactiveMonitorPluginsByAgentId\"";
        String query = mapper.substring(mapper.indexOf(marker), mapper.indexOf("</select>",
                mapper.indexOf(marker)));
        assertTrue(query.contains("p.provider_code IN ('get_weather', 'get_news_from_newsnow')"));
        assertTrue(query.contains("m.param_info AS paramInfo"));
        assertFalse(query.contains("ai_knowledge_base"));
        assertFalse(query.contains("api_key"));
    }

    @Test
    void pendingRouteUsesDeviceTokenFilterBeforeOauthFallback() {
        var bean = ShiroConfig.shirFilter(
                org.mockito.Mockito.mock(org.apache.shiro.web.mgt.WebSecurityManager.class),
                org.mockito.Mockito.mock(xiaozhi.modules.sys.service.SysParamsService.class));
        var chains = bean.getFilterChainDefinitionMap();
        assertEquals("device", chains.get("/device/proactive/pending"));
        assertTrue(new java.util.ArrayList<>(chains.keySet()).indexOf("/device/proactive/pending")
                < new java.util.ArrayList<>(chains.keySet()).indexOf("/**"));
    }

    private static String valueOf(String json, String field) {
        var matcher = java.util.regex.Pattern.compile("\\\"" + field + "\\\":(-?\\d+)").matcher(json);
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private static String fieldToProperty(String field) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char c : field.toCharArray()) {
            if (c == '_') upper = true;
            else {
                result.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return result.toString();
    }
}
