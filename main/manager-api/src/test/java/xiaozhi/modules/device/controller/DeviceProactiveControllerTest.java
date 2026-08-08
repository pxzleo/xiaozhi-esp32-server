package xiaozhi.modules.device.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import xiaozhi.modules.device.proactive.ProactiveDTOs.ClassifierAvailabilityView;
import xiaozhi.modules.device.proactive.ProactiveDTOs.MonitorsView;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.device.proactive.ProactiveService;
import xiaozhi.modules.security.user.SecurityUser;

class DeviceProactiveControllerTest {
    @Test
    void monitorReadUsesNormalPermissionAndCurrentUserOwnershipScope() {
        RequiresPermissions permission = DeviceProactiveController.class
                .getAnnotation(RequiresPermissions.class);
        assertArrayEquals(new String[] {"sys:role:normal"}, permission.value());

        ProactiveMonitorService monitorService = mock(ProactiveMonitorService.class);
        MonitorsView view = new MonitorsView("device-1", null, null, "广州", null, true,
                new ClassifierAvailabilityView(true, true, null));
        when(monitorService.getMonitors(7L, "device-1")).thenReturn(view);
        DeviceProactiveController controller = new DeviceProactiveController(
                mock(ProactiveService.class), monitorService);

        try (MockedStatic<SecurityUser> securityUser = mockStatic(SecurityUser.class)) {
            securityUser.when(SecurityUser::getUserId).thenReturn(7L);
            assertEquals(view, controller.monitors("device-1").getData());
        }
        verify(monitorService).getMonitors(7L, "device-1");
    }

    @Test
    void unknownHazardOrNewsCategoryIsRejectedBeforeControllerService() throws Exception {
        ProactiveMonitorService monitorService = mock(ProactiveMonitorService.class);
        DeviceProactiveController controller = new DeviceProactiveController(
                mock(ProactiveService.class), monitorService);
        var mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        String request = """
                {"weather":{"enabled":true,"interval_minutes":30,"config":{
                "hazard_types":["typhoon"]}},"news":{"enabled":true,"interval_minutes":10,
                "config":{"categories":[]}}}
                """;

        mockMvc.perform(put("/device/proactive/monitors/device-1")
                .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/device/proactive/monitors/device-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.replace("\"typhoon\"", "\"rainstorm\"")
                        .replace("\"categories\":[]", "\"categories\":[\"celebrity_gossip\"]")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/device/proactive/monitors/device-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request.replace("\"typhoon\"", "\"rainstorm\"")
                        .replace("\"hazard_types\":[\"rainstorm\"]",
                                "\"hazard_types\":[\"rainstorm\"],"
                                        + "\"minimum_warning_severity\":\"warning\"")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(monitorService);
    }
}
