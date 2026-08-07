package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import xiaozhi.modules.security.config.WebMvcConfig;

class DeviceNeteaseControllerContractTest {
    private static final String DEVICE = "11:22:33:44:55:66";
    private final DeviceNeteaseService service = mock(DeviceNeteaseService.class);
    private final DeviceNeteaseController controller = new DeviceNeteaseController(
            service, "https://device.example.com/xiaozhi");

    @BeforeEach
    void resetServiceResponses() {
        org.mockito.Mockito.reset(service);
    }

    @Test
    void exposesFirmwareLoginAndAuthorizationStatusEnums() {
        when(service.status(DEVICE)).thenReturn(status(NeteaseLoginStatus.LOGGED_IN));
        assertEquals("logged_in", controller.status(DEVICE).getData().status());

        when(service.poll(DEVICE, "session")).thenReturn(status(NeteaseLoginStatus.WAITING_SCAN));
        assertEquals("pending", controller.poll(DEVICE, "session").getData().status());
        when(service.poll(DEVICE, "session")).thenReturn(status(NeteaseLoginStatus.SCANNED));
        assertEquals("pending", controller.poll(DEVICE, "session").getData().status());
        when(service.poll(DEVICE, "session")).thenReturn(status(NeteaseLoginStatus.LOGGED_IN));
        assertEquals("authorized", controller.poll(DEVICE, "session").getData().status());
        when(service.poll(DEVICE, "session")).thenReturn(status(NeteaseLoginStatus.EXPIRED));
        assertEquals("expired", controller.poll(DEVICE, "session").getData().status());
        when(service.poll(DEVICE, "session")).thenReturn(status(NeteaseLoginStatus.FAILED));
        assertEquals("failed", controller.poll(DEVICE, "session").getData().status());
        when(service.poll(DEVICE, "session")).thenReturn(
                new DeviceNeteaseService.StatusResponse(NeteaseLoginStatus.FAILED,
                        "session", null, null, null, null, "USER_LOGOUT"));
        assertEquals("cancelled", controller.poll(DEVICE, "session").getData().status());
    }

    @Test
    void exposesHttpsQrImageUrlAndMillisecondTiming() throws Exception {
        when(service.createSession(DEVICE)).thenReturn(new DeviceNeteaseService.SessionResponse(
                "opaque-session", "not-returned", Date.from(Instant.now().plusSeconds(60)), 2));

        DeviceNeteaseController.LoginSessionResponse data = controller.create(DEVICE).getData();

        assertEquals("opaque-session", data.sessionId());
        assertEquals("https://device.example.com/xiaozhi/device/netease/sessions/opaque-session/qr-image",
                data.qrImageUrl());
        assertTrue(data.expiresInMs() > 0 && data.expiresInMs() <= 60_000);
        assertEquals(2_000, data.pollIntervalMs());
        JsonNode json = new WebMvcConfig().jackson2HttpMessageConverter().getObjectMapper()
                .valueToTree(data);
        assertTrue(json.has("session_id"));
        assertTrue(json.has("qr_image_url"));
        assertTrue(json.get("expires_in_ms").isInt());
        assertTrue(json.get("poll_interval_ms").isInt());
    }

    @Test
    void acceptsLanHttpQrImageUrl() {
        when(service.createSession(DEVICE)).thenReturn(new DeviceNeteaseService.SessionResponse(
                "opaque-session", "not-returned", Date.from(Instant.now().plusSeconds(60)), 2));
        DeviceNeteaseController lanController = new DeviceNeteaseController(
                service, "http://192.168.100.149/xiaozhi");

        assertEquals("http://192.168.100.149/xiaozhi/device/netease/sessions/opaque-session/qr-image",
                lanController.create(DEVICE).getData().qrImageUrl());
    }

    @Test
    void mapsLogoutOutcomesToFirmwareContract() {
        when(service.logout(DEVICE)).thenReturn(
                new DeviceNeteaseService.LogoutResponse("LOGGED_OUT", false, "USER_LOGOUT"));
        assertEquals("logged_out", controller.logout(DEVICE).getData().status());

        when(service.logout(DEVICE)).thenReturn(
                new DeviceNeteaseService.LogoutResponse("ALREADY_LOGGED_OUT", false, null));
        assertEquals("already_logged_out", controller.logout(DEVICE).getData().status());
    }

    @Test
    void rejectsUnsafePublicBaseUrlsBeforeCreatingSession() {
        assertThrows(xiaozhi.common.exception.RenException.class,
                () -> new DeviceNeteaseController(service, "https://user:secret@device.example.com/xiaozhi")
                        .create(DEVICE));
        assertThrows(xiaozhi.common.exception.RenException.class,
                () -> new DeviceNeteaseController(service, "https://device.example.com/xiaozhi?tenant=one")
                        .create(DEVICE));
        assertThrows(xiaozhi.common.exception.RenException.class,
                () -> new DeviceNeteaseController(service, "https://device.example.com/xiaozhi#fragment")
                        .create(DEVICE));
    }

    private DeviceNeteaseService.StatusResponse status(NeteaseLoginStatus status) {
        return new DeviceNeteaseService.StatusResponse(status, null, null, null, null, null, null);
    }
}
