package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;

class MobileEventContractTest {
    private final ObjectMapper mapper = new MobileJsonHttpMessageConverter().getObjectMapper();

    @Test
    void legacyM2ConfigOmitsLocationFieldButLocationBindingIncludesIt() throws Exception {
        var notification = new MobileEventDTOs.NotificationConfig(true, 200, 50, java.util.List.of("message"));
        String legacy = mapper.writeValueAsString(new MobileEventDTOs.ConfigResponse(1, notification, null));
        assertFalse(legacy.contains("location_gateway"));
        String location = mapper.writeValueAsString(new MobileEventDTOs.ConfigResponse(1, notification,
                new MobileEventDTOs.LocationConfig(true)));
        assertTrue(location.contains("\"location_gateway\":{\"available\":true}"));
    }

    @Test
    void eventBatchRejectsUnknownFieldsAndTrailingJson() {
        String valid = "{\"version\":1,\"events\":[]}";
        assertThrows(Exception.class, () -> mapper.readValue("{\"version\":1,\"events\":[],\"raw_text\":\"x\"}",
                MobileEventDTOs.BatchRequest.class));
        assertThrows(Exception.class, () -> mapper.readValue(valid + "{}", MobileEventDTOs.BatchRequest.class));
    }

    @Test
    void mobileM2RoutesBypassAccountOauthOnlyBecauseControllerValidatesMobileCredential() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/xiaozhi/modules/security/config/ShiroConfig.java"));
        assertTrue(source.contains("/mobile/config\", \"anon"));
        assertTrue(source.contains("/mobile/events:batch\", \"anon"));
        assertTrue(source.contains("/mobile/events/status\", \"anon"));
    }

    @Test
    void eventControllerUsesMobileAdviceAndPreservesRealHttp401() throws Exception {
        MobileEventService service = mock(MobileEventService.class);
        when(service.config(any())).thenThrow(new MobileApiException(org.springframework.http.HttpStatus.UNAUTHORIZED,
                "MOBILE_CREDENTIAL_INVALID", "手机凭据无效或已撤销"));
        var mvc = MockMvcBuilders.standaloneSetup(new MobileEventController(service))
                .setControllerAdvice(new MobileApiExceptionHandler()).build();
        mvc.perform(get("/mobile/config")
                .header("Authorization", "Bearer secret")
                .header("Mobile-Instance-Id", "mob_0123456789abcdef0123456789abcdef")
                .header("Client-Id", "123e4567-e89b-12d3-a456-426614174000")
                .header("Mobile-Credential-Version", "1")
                .header("Mobile-Protocol-Version", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("MOBILE_CREDENTIAL_INVALID"));
    }

    @Test
    void missingAuthorizationIs401ButMissingProtocolHeaderIs400() throws Exception {
        MobileEventService service = mock(MobileEventService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new MobileEventController(service))
                .setControllerAdvice(new MobileApiExceptionHandler()).build();
        var base = get("/mobile/config")
                .header("Mobile-Instance-Id", "mob_0123456789abcdef0123456789abcdef")
                .header("Client-Id", "123e4567-e89b-12d3-a456-426614174000")
                .header("Mobile-Credential-Version", "1")
                .header("Mobile-Protocol-Version", "1");
        mvc.perform(base).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error_code").value("MOBILE_CREDENTIAL_MISSING"));

        mvc.perform(get("/mobile/config")
                .header("Authorization", "Bearer secret")
                .header("Mobile-Instance-Id", "mob_0123456789abcdef0123456789abcdef")
                .header("Client-Id", "123e4567-e89b-12d3-a456-426614174000")
                .header("Mobile-Credential-Version", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("MOBILE_PROTOCOL_HEADER_MISSING"));
    }
}
