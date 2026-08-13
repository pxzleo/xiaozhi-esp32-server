package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;

import jakarta.validation.Validation;
import com.fasterxml.jackson.databind.ObjectMapper;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PendingEnvelope;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

class MobileProactiveContractTest {
    @Test
    void routesAreFrozenAndPendingHasNoAuthorityText() throws Exception {
        Method pending = MobileProactiveController.class.getMethod("pending", String.class,
                String.class, String.class, int.class, int.class);
        assertTrue(Arrays.asList(pending.getAnnotation(GetMapping.class).value())
                .contains("/mobile/proactive/pending"));
        Method claim = Arrays.stream(MobileProactiveController.class.getMethods())
                .filter(method -> method.getName().equals("claim")).findFirst().orElseThrow();
        Method complete = Arrays.stream(MobileProactiveController.class.getMethods())
                .filter(method -> method.getName().equals("complete")).findFirst().orElseThrow();
        assertTrue(Arrays.asList(claim.getAnnotation(PostMapping.class).value())
                .contains("/mobile/proactive/{eventId}:claim"));
        assertTrue(Arrays.asList(complete.getAnnotation(PostMapping.class).value())
                .contains("/mobile/proactive/{eventId}:complete"));
        Method quietGet = Arrays.stream(MobileProactiveController.class.getMethods())
                .filter(method -> method.getName().equals("quietHours")).findFirst().orElseThrow();
        Method quietPut = Arrays.stream(MobileProactiveController.class.getMethods())
                .filter(method -> method.getName().equals("updateQuietHours")).findFirst().orElseThrow();
        assertTrue(Arrays.asList(quietGet.getAnnotation(GetMapping.class).value())
                .contains("/mobile/proactive/quiet-hours"));
        assertTrue(Arrays.asList(quietPut.getAnnotation(PutMapping.class).value())
                .contains("/mobile/proactive/quiet-hours"));
        var fields = Arrays.stream(MobileProactiveDTOs.PendingResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(fields.contains("title"));
        assertFalse(fields.contains("summary"));
        assertFalse(fields.contains("tts"));
    }

    @Test
    void terminalValidationRequiresControlledReasonAndAuditUsesClaimToken() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new MobileProactiveDTOs.CompleteRequest();
            request.version = 1;
            request.claimToken = "123e4567-e89b-42d3-a456-426614174000";
            request.status = "interrupted";
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.reason = "phone_call";
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
        Method audit = MobileProactiveAuditDao.class.getMethod("recordTerminal", String.class,
                String.class, String.class, String.class, String.class, java.util.Date.class);
        String sql = audit.getAnnotation(Update.class).value()[0];
        assertTrue(sql.contains("claim_token = #{claimToken}"));
        assertTrue(sql.contains("mobile_terminal_status = #{status}"));
        assertTrue(sql.contains("mobile_terminal_reason = #{reason}"));
    }

    @Test
    void proactiveTimesSerializeAsUnixMillisecondsAndTokensAreUuidV4() throws Exception {
        var expires = new java.util.Date(1_800_000_000_123L);
        var envelope = new PendingEnvelope(true, "ext-1", Topic.NEWS, Priority.HIGH,
                new java.util.Date(1_700_000_000_000L), expires, 300);
        String json = new ObjectMapper().writeValueAsString(
                MobileProactiveDTOs.PendingResponse.from(envelope));
        assertTrue(json.contains("\"expires_at\":1800000000123"));
        assertFalse(json.contains("1800000000123\""));

        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var request = new MobileProactiveDTOs.ClaimRequest();
            request.version = 1;
            request.claimToken = "not-a-uuid";
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.claimToken = "123e4567-e89b-42d3-a456-426614174000";
            assertTrue(factory.getValidator().validate(request).isEmpty());
        }
    }

    @Test
    void quietHoursRequireBothValidDifferentValues() throws Exception {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var mapper = new ObjectMapper();
            var missing = mapper.readValue("{\"version\":1}", MobileProactiveDTOs.QuietHoursRequest.class);
            assertFalse(factory.getValidator().validate(missing).isEmpty());
            var request = new MobileProactiveDTOs.QuietHoursRequest();
            request.version = 1;
            request.setQuietStart("22:00");
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.setQuietEnd("07:00");
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setQuietEnd("22:00");
            assertFalse(factory.getValidator().validate(request).isEmpty());
            var clear = mapper.readValue(
                    "{\"version\":1,\"quiet_start\":null,\"quiet_end\":null}",
                    MobileProactiveDTOs.QuietHoursRequest.class);
            assertTrue(factory.getValidator().validate(clear).isEmpty());
            assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,
                    () -> mapper.readValue(
                            "{\"version\":1,\"quiet_start\":null,\"quiet_end\":null,\"extra\":1}",
                            MobileProactiveDTOs.QuietHoursRequest.class));
        }
    }

    @Test
    void shiroLetsControllerValidateMobileCredentialBeforeOauthCatchAll() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/xiaozhi/modules/security/config/ShiroConfig.java"));
        int mobile = source.indexOf("filterMap.put(\"/mobile/proactive/**\", \"anon\")");
        int history = source.indexOf("filterMap.put(\"/mobile/chat-history\", \"anon\")");
        int fallback = source.indexOf("filterMap.put(\"/**\", \"oauth2\")");
        assertTrue(mobile >= 0 && fallback > mobile);
        assertTrue(history >= 0 && fallback > history);
    }
}
