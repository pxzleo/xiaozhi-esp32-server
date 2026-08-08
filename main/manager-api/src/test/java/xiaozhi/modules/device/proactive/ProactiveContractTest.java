package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

class ProactiveContractTest {
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
        assertTrue(restoreSql.contains("WHEN previous_mode = 'AGGRESSIVE' THEN 0"));
        assertTrue(restoreSql.contains("previous_daily_limit = NULL"));

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
        assertTrue(claimSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
    }
}
