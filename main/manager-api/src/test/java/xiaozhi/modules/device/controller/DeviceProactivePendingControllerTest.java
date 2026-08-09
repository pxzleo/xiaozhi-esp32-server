package xiaozhi.modules.device.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.device.proactive.ProactiveDTOs.PendingEnvelope;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.security.config.WebMvcConfig;

class DeviceProactivePendingControllerTest {
    @Test
    void pendingResponseHasExactContentLengthAndKeepsDeviceDateContract() throws Exception {
        ProactiveMonitorService service = mock(ProactiveMonitorService.class);
        PendingEnvelope envelope = new PendingEnvelope(true, "event-1", Topic.NEWS,
                Priority.HIGH, new Date(1786233600000L), new Date(1786237200000L), 0);
        when(service.pending("aa:bb:cc:dd:ee:ff")).thenReturn(envelope);
        var converter = new WebMvcConfig().jackson2HttpMessageConverter();
        DeviceProactivePendingController controller = new DeviceProactivePendingController(
                service, converter);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new ByteArrayHttpMessageConverter(), converter)
                .build();

        var result = mockMvc.perform(get("/device/proactive/pending")
                .header("Device-Id", "aa:bb:cc:dd:ee:ff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topic").value("news"))
                .andExpect(jsonPath("$.data.priority").value("high"))
                .andExpect(jsonPath("$.data.created_at").value("2026-08-09 08:00:00"))
                .andExpect(jsonPath("$.data.expires_at").value("2026-08-09 09:00:00"))
                .andExpect(header().exists("Content-Length"))
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertEquals(body.length,
                Integer.parseInt(result.getResponse().getHeader("Content-Length")));
        assertEquals(new String(body, StandardCharsets.UTF_8),
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
