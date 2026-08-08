package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.device.proactive.ProactiveDTOs.MonitorsUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.NewsMonitorConfig;
import xiaozhi.modules.device.proactive.ProactiveDTOs.WeatherMonitorConfig;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.security.config.ShiroConfig;

class ProactiveMonitorContractTest {
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
                "source":"agent_plugin","hazard_types":[],"official_min_severity":"warning",
                "precip_probability":70,"wind_speed_kmh":62,"high_temp_c":35,"low_temp_c":0,
                "temp_drop_24h_c":8,"forecast_hours":6,"cooldown_minutes":720,"unknown":1}},
                "news":{"enabled":true,"interval_minutes":10,"config":{"source_mode":"agent_plugin",
                "sources":[],"categories":[],"confidence":0.85,"cooldown_minutes":120,
                "dedupe_hours":24,"scope":"domestic_and_international"}}}
                """;
        assertThrows(Exception.class, () -> new ObjectMapper().readValue(json, MonitorsUpdate.class));
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
                {"source":"agent_plugin","hazard_types":[],"official_min_severity":"warning",
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
    }

    @Test
    void leaseSqlContractUsesDatabaseClockAndCasPredicates() throws Exception {
        Method candidates = ProactiveMonitorDao.class.getMethod("selectDueCandidates", int.class);
        String select = candidates.getAnnotation(Select.class).value()[0];
        assertTrue(select.contains("last_probe_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)"));
        assertTrue(select.contains("LIMIT #{limit}"));
        assertTrue(select.contains("lease_until IS NULL OR m.lease_until <= CURRENT_TIMESTAMP"));
        assertFalse(select.contains("#{now}"));
        assertFalse(select.contains("#{activeCutoff}"));

        Method claim = ProactiveMonitorDao.class.getMethod("claimCas", String.class, String.class,
                String.class, String.class);
        String claimSql = claim.getAnnotation(Update.class).value()[0];
        assertTrue(claimSql.contains("lease_until = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 120 SECOND)"));
        assertTrue(claimSql.contains("lease_until IS NULL OR lease_until <= CURRENT_TIMESTAMP"));
        assertTrue(claimSql.contains("last_probe_at >= DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 15 MINUTE)"));
        assertTrue(claimSql.contains("updated_at = CURRENT_TIMESTAMP"));
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
        assertTrue(sql.contains("event_type IN ('WEATHER_ALERT', 'NEWS_ALERT')"));
        assertTrue(sql.contains("delivery_status = 'PENDING'"));
        assertTrue(sql.contains("claimed_at < #{claimCutoff}"));
        assertFalse(java.util.Arrays.stream(ProactiveDTOs.PendingEnvelope.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("payload") || component.getName().equals("reason")));
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
