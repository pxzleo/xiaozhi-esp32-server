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
    }

    @Test
    void preferenceValidationEnforcesModeLimitQuietWindowAndTopicSeparation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            PreferenceUpdate request = new PreferenceUpdate();
            request.setMode(Mode.ACTIVE);
            request.setDailyLimit(4);
            request.setAllowedTopics(Set.of(Topic.MUSIC));
            request.setBlockedTopics(Set.of(Topic.MUSIC));
            assertFalse(validator.validate(request).isEmpty());

            request.setDailyLimit(3);
            request.setBlockedTopics(Set.of(Topic.WEATHER));
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

        Method status = ProactiveEventDao.class.getMethod("updateStatus", String.class, String.class,
                String.class, String.class, java.util.Date.class);
        String eventSql = status.getAnnotation(Update.class).value()[0];
        assertTrue(eventSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
    }
}
