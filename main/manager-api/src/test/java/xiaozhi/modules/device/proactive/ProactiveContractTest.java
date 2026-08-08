package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveEnums.DedupePolicy;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

class ProactiveContractTest {
    @Test
    void externalRollingPoliciesHaveFixedWindowsAndLegacyEventsNeedNoPolicy() {
        EventUpsert news = new EventUpsert();
        news.setEventType(EventType.NEWS_ALERT);
        news.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        news.setDedupeWindowHours(24);
        assertTrue(news.isDedupePolicyValid());
        news.setDedupeWindowHours(23);
        assertFalse(news.isDedupePolicyValid());

        EventUpsert warning = new EventUpsert();
        warning.setEventType(EventType.WEATHER_ALERT);
        warning.setDedupePolicy(DedupePolicy.EVENT_ID);
        assertTrue(warning.isDedupePolicyValid());
        EventUpsert forecast = new EventUpsert();
        forecast.setEventType(EventType.WEATHER_ALERT);
        forecast.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        forecast.setDedupeWindowHours(12);
        assertTrue(forecast.isDedupePolicyValid());

        EventUpsert reminder = new EventUpsert();
        reminder.setEventType(EventType.REMINDER);
        assertTrue(reminder.isDedupePolicyValid());
    }

    @Test
    void rollingDedupeLedgerUsesDatabaseClockUniqueLockAndHashOnlyStorage() throws Exception {
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/changelog/202608090130.sql"));
        assertTrue(migration.contains("PRIMARY KEY (`device_id`, `event_type`, `dedupe_hash`)"));
        assertTrue(migration.contains("`dedupe_hash` char(64)"));
        assertTrue(migration.contains("ON DELETE CASCADE"));
        assertFalse(migration.contains("title"));
        assertFalse(migration.contains("url"));

        Method insert = ProactiveEventDedupeDao.class.getMethod("insertIfAbsent",
                String.class, String.class, String.class);
        String insertSql = insert.getAnnotation(Insert.class).value()[0];
        assertTrue(insertSql.contains("INSERT IGNORE"));
        assertTrue(insertSql.contains("CURRENT_TIMESTAMP"));
        Method lock = ProactiveEventDedupeDao.class.getMethod("selectForUpdate",
                String.class, String.class, String.class);
        assertTrue(lock.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0]
                .contains("FOR UPDATE"));
        Method recent = ProactiveEventDedupeDao.class.getMethod("selectRecentEventId",
                String.class, String.class, String.class, int.class);
        String recentSql = recent.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(recentSql.contains("last_created_at > DATE_SUB(CURRENT_TIMESTAMP,"));
        assertTrue(recentSql.contains("INTERVAL #{windowHours} HOUR"));
        assertFalse(recentSql.contains("UTC_DATE"));
        Method mark = ProactiveEventDedupeDao.class.getMethod("markCreated",
                String.class, String.class, String.class, String.class);
        assertTrue(mark.getAnnotation(Update.class).value()[0]
                .contains("last_created_at = CURRENT_TIMESTAMP"));

        Method globalGate = ProactiveGlobalDao.class.getMethod(
                "selectExternalMonitoringValueForUpdate");
        String globalGateSql = globalGate.getAnnotation(Select.class).value()[0];
        assertTrue(globalGateSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(globalGateSql.contains("FOR UPDATE"));
    }

    @Test
    void migrationAddsOnlyScopedTablesAndRequiredIdempotencyKeys() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081200.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ai_device_proactive_preference"));
            assertTrue(sql.contains("ai_device_proactive_event"));
            assertTrue(sql.contains("ai_device_proactive_habit"));
            assertTrue(sql.contains("UNIQUE KEY `uk_ai_device_proactive_event_id` (`event_id`)"));
            assertTrue(sql.contains("UNIQUE KEY `uk_ai_device_proactive_event_dedupe` (`device_id`, `dedupe_key`)"));
            assertTrue(sql.contains("UNIQUE KEY `uk_ai_device_proactive_habit` (`device_id`, `habit_type`, `habit_key`)"));
            assertFalse(sql.toLowerCase().contains("chain_of_thought"));
        }
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081500.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN `previous_daily_limit`"));
            assertTrue(sql.contains("WHERE `mode` = 'TODAY_SILENT' AND `previous_daily_limit` IS NULL"));
            assertTrue(sql.contains("WHEN 'CONSERVATIVE' THEN 1"));
            assertTrue(sql.contains("WHEN 'ACTIVE' THEN 3"));
            assertTrue(sql.contains("WHEN 'AGGRESSIVE' THEN 5"));
            assertTrue(sql.contains("ELSE 5"));
            assertTrue(sql.contains("DROP INDEX `uk_ai_device_proactive_preference_mac`"));
            assertTrue(sql.contains("ADD KEY `idx_ai_device_proactive_preference_mac`"));
            assertTrue(sql.contains("ADD UNIQUE KEY `uk_ai_device_proactive_event_device_event` (`device_id`, `event_id`)"));
            assertTrue(sql.contains("ADD KEY `idx_ai_device_proactive_event_dedupe` (`device_id`, `dedupe_key`)"));
        }
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081700.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD KEY `idx_ai_device_user_id_id` (`user_id`, `id`)"));
        }
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081900.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN `claim_token` varchar(64)"));
            assertTrue(sql.contains("ADD COLUMN `claimed_at` datetime"));
            assertTrue(sql.contains("idx_ai_device_proactive_event_claim"));
        }
    }

    @Test
    void preferenceValidationEnforcesModeLimitQuietWindowAndTopicSeparation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            PreferenceUpdate request = new PreferenceUpdate();
            request.setMode(Mode.ACTIVE);
            request.setDailyLimit(6);
            request.setAllowedTopics(Set.of(Topic.MUSIC));
            request.setBlockedTopics(Set.of(Topic.MUSIC));
            assertFalse(validator.validate(request).isEmpty());

            request.setDailyLimit(5);
            request.setBlockedTopics(Set.of(Topic.WEATHER));
            assertTrue(validator.validate(request).isEmpty());

            request.setMode(Mode.AGGRESSIVE);
            request.setDailyLimit(1);
            assertFalse(validator.validate(request).isEmpty());
            request.setDailyLimit(0);
            assertTrue(validator.validate(request).isEmpty());
        }
    }

    @Test
    void unknownRequestFieldsAreRejectedEvenThoughGlobalMapperIsLenient() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"mode\":\"aggressive\",\"hidden_prompt\":\"secret\"}", PreferenceUpdate.class));
    }

    @Test
    void modeWireContractUsesStrictLowercaseValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("\"aggressive\"", mapper.writeValueAsString(Mode.AGGRESSIVE));
        assertEquals(Mode.AGGRESSIVE, mapper.readValue("\"aggressive\"", Mode.class));
        assertThrows(Exception.class, () -> mapper.readValue("\"AGGRESSIVE\"", Mode.class));
    }

    @Test
    void habitObservationUsesAtomicMysqlIncrementAndEventStatusScopesDevice() throws Exception {
        Method observe = ProactiveHabitDao.class.getMethod("observeAtomic", ProactiveHabitEntity.class);
        String habitSql = observe.getAnnotation(Insert.class).value()[0];
        assertTrue(habitSql.contains("evidence_count = evidence_count + VALUES(evidence_count)"));
        assertTrue(habitSql.contains("ON DUPLICATE KEY UPDATE"));

        Method status = ProactiveEventDao.class.getMethod("updateStatusCas", String.class, String.class,
                String.class, String.class, String.class, String.class, java.util.Date.class);
        String eventSql = status.getAnnotation(Update.class).value()[0];
        assertTrue(eventSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
        assertTrue(eventSql.contains("delivery_status = #{expectedStatus}"));
        assertTrue(eventSql.contains("claim_token = #{claimToken}"));

        Method findEvent = ProactiveEventDao.class.getMethod("selectByDeviceAndEventId",
                String.class, String.class);
        String findSql = findEvent.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(findSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
        assertFalse(findSql.contains(" OR "));

        Method restore = ProactivePreferenceDao.class.getMethod("restoreExpiredSilent",
                String.class, java.util.Date.class);
        String restoreSql = restore.getAnnotation(Update.class).value()[0];
        assertTrue(restoreSql.contains("WHEN 'AGGRESSIVE' THEN 0"));
        assertTrue(restoreSql.contains("previous_daily_limit = NULL"));

        Method normalize = ProactivePreferenceDao.class.getMethod("normalizeLegacyAggressiveLimit",
                String.class, Integer.class, Integer.class, java.util.Date.class);
        String normalizeSql = normalize.getAnnotation(Update.class).value()[0];
        assertTrue(normalizeSql.contains("SET daily_limit = 0"));
        assertTrue(normalizeSql.contains("version = version + 1"));
        assertTrue(normalizeSql.contains("mode = 'AGGRESSIVE'"));
        assertTrue(normalizeSql.contains("daily_limit = #{expectedDailyLimit}"));
        assertTrue(normalizeSql.contains("daily_limit BETWEEN 1 AND 5"));
        assertTrue(normalizeSql.contains("version = #{expectedVersion}"));
        String normalizeSet = normalizeSql.substring(normalizeSql.indexOf("SET "),
                normalizeSql.indexOf("\nWHERE"));
        assertEquals("SET daily_limit = 0, version = version + 1, updated_at = #{now}",
                normalizeSet);

        Method normalizePrevious = ProactivePreferenceDao.class.getMethod(
                "normalizeLegacyPreviousAggressiveLimit", String.class, Integer.class,
                Integer.class, java.util.Date.class);
        String normalizePreviousSql = normalizePrevious.getAnnotation(Update.class).value()[0];
        assertTrue(normalizePreviousSql.contains("SET previous_daily_limit = 0"));
        assertTrue(normalizePreviousSql.contains("mode = 'TODAY_SILENT'"));
        assertTrue(normalizePreviousSql.contains("previous_mode = 'AGGRESSIVE'"));
        assertTrue(normalizePreviousSql.contains("previous_daily_limit BETWEEN 1 AND 5"));
        String normalizePreviousSet = normalizePreviousSql.substring(
                normalizePreviousSql.indexOf("SET "), normalizePreviousSql.indexOf("\nWHERE"));
        assertEquals("SET previous_daily_limit = 0, version = version + 1, updated_at = #{now}",
                normalizePreviousSet);

        Method lockedPreference = ProactivePreferenceDao.class.getMethod(
                "selectByIdForUpdate", String.class);
        String lockedPreferenceSql = lockedPreference.getAnnotation(
                org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(lockedPreferenceSql.contains("device_id = #{deviceId}"));
        assertTrue(lockedPreferenceSql.contains("FOR UPDATE"));

        Method lockedFind = ProactiveEventDao.class.getMethod("selectByDeviceAndEventIdForUpdate",
                String.class, String.class);
        String lockedFindSql = lockedFind.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(lockedFindSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
        assertTrue(lockedFindSql.contains("FOR UPDATE"));
        assertFalse(lockedFindSql.contains(" OR "));

        Method insertIfAbsent = ProactiveEventDao.class.getMethod("insertIfAbsent", ProactiveEventEntity.class);
        String insertSql = insertIfAbsent.getAnnotation(Insert.class).value()[0];
        assertTrue(insertSql.contains("ON DUPLICATE KEY UPDATE id = id"));

        Method claim = ProactiveEventDao.class.getMethod("claimPending", String.class,
                String.class, String.class, java.util.Date.class, java.util.Date.class);
        String claimSql = claim.getAnnotation(Update.class).value()[0];
        assertTrue(claimSql.contains("delivery_status = 'CLAIMED'"));
        assertTrue(claimSql.contains("delivery_status = 'PENDING'"));
        assertTrue(claimSql.contains("claim_token = #{claimToken}"));
        assertTrue(claimSql.contains("claimed_at < #{leaseCutoff}"));
        assertTrue(claimSql.contains("claimed_at IS NULL"));
        assertTrue(claimSql.contains("e.device_id = #{deviceId} AND e.event_id = #{eventId}"));
        assertTrue(claimSql.contains("LEFT JOIN ai_device_proactive_monitor m"));
        assertTrue(claimSql.contains("WHEN 'WEATHER_ALERT' THEN 'WEATHER'"));
        assertTrue(claimSql.contains("WHEN 'NEWS_ALERT' THEN 'NEWS'"));
        assertTrue(claimSql.contains("m.enabled = 1"));
        assertTrue(claimSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(claimSql.contains("LOWER(TRIM(g.param_value)) = 'true'"));
        assertTrue(claimSql.contains("e.event_type NOT IN ('WEATHER_ALERT', 'NEWS_ALERT')"));
        assertFalse(claimSql.contains("priority"));

        Method monitorRead = ProactiveEventDao.class.getMethod(
                "selectMonitorEventByMacAndEventId", String.class, String.class);
        String monitorReadSql = monitorRead.getAnnotation(Select.class).value()[0];
        assertTrue(monitorReadSql.contains("INNER JOIN ai_device_proactive_monitor m"));
        assertTrue(monitorReadSql.contains("m.enabled = 1"));
        assertTrue(monitorReadSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(monitorReadSql.contains("LOWER(TRIM(g.param_value)) = 'true'"));
        assertTrue(monitorReadSql.contains("WHEN 'WEATHER_ALERT' THEN 'WEATHER'"));
        assertTrue(monitorReadSql.contains("WHEN 'NEWS_ALERT' THEN 'NEWS'"));
    }
}
