package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class MobileAssistantContractTest {
    private final ObjectMapper mapper = new MobileJsonHttpMessageConverter().getObjectMapper();

    @Test
    void bindDtoRejectsUnknownFieldsAndTrailingJson() {
        String valid = "{\"version\":1,\"installation_id\":\"123e4567-e89b-12d3-a456-426614174000\","
                + "\"stable_device_key\":\"" + "a".repeat(64) + "\","
                + "\"platform\":\"android\",\"app_version\":\"0.1.0\",\"agent_id\":\"agent-1\","
                + "\"capabilities\":[\"text_chat\"]}";
        assertThrows(Exception.class, () -> mapper.readValue(valid.replace("}", ",\"imei\":\"forbidden\"}"),
                MobileAssistantDTOs.BindRequest.class));
        assertThrows(Exception.class, () -> mapper.readValue(valid + "{}", MobileAssistantDTOs.BindRequest.class));
    }

    @Test
    void mobileRoutesHaveExplicitAuthenticationBoundaries() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/xiaozhi/modules/security/config/ShiroConfig.java"));
        assertTrue(source.contains("/config/mobile/**\", \"server"));
        assertTrue(source.indexOf("/config/mobile/**") < source.indexOf("/**\", \"oauth2"));
    }

    @Test
    void controlledMobileErrorsCarryHttpStatusVersionAndCode() {
        var response = new MobileApiExceptionHandler().handleMobileApiException(
                new MobileApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                        "UNSUPPORTED_VERSION", "不支持的手机协议版本"));

        assertEquals(400, response.getStatusCode().value());
        assertEquals(1, response.getBody().version());
        assertEquals("UNSUPPORTED_VERSION", response.getBody().errorCode());
    }
}
