package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class DeviceNeteaseServiceTest {
    private static final String DEVICE = "11:22:33:44:55:66";
    private static final String VALID_PNG_DATA_URL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<DeviceNeteaseSessionEntity> session = new AtomicReference<>();
    private final AtomicReference<DeviceNeteaseAuthEntity> auth = new AtomicReference<>();
    private NeteaseProviderClient provider;
    private NeteaseLoginCompletionService completionService;
    private DeviceNeteaseSessionDao sessionDao;
    private DeviceDao deviceDao;
    private DeviceNeteaseAuthDao authDao;
    private NeteaseSessionReservationService reservationService;
    private DeviceNeteaseService service;

    @BeforeEach
    void setUp() {
        deviceDao = mock(DeviceDao.class);
        authDao = mock(DeviceNeteaseAuthDao.class);
        sessionDao = mock(DeviceNeteaseSessionDao.class);
        NeteaseCredentialCipher cipher = mock(NeteaseCredentialCipher.class);
        provider = mock(NeteaseProviderClient.class);
        completionService = mock(NeteaseLoginCompletionService.class);
        reservationService = mock(NeteaseSessionReservationService.class);
        DeviceEntity boundDevice = new DeviceEntity();
        boundDevice.setId("device-record-id");
        boundDevice.setMacAddress(DEVICE);
        boundDevice.setUserId(1L);
        when(deviceDao.selectById(any())).thenReturn(null);
        when(deviceDao.selectOne(any())).thenReturn(boundDevice);
        DeviceEntity otherDevice = new DeviceEntity();
        otherDevice.setId("other-device");
        otherDevice.setMacAddress("AA:BB:CC:DD:EE:FF");
        otherDevice.setUserId(2L);
        when(deviceDao.selectById("other-device")).thenReturn(otherDevice);
        when(deviceDao.selectByIdentifierForUpdate(any())).thenReturn(boundDevice);
        when(authDao.selectById(any())).thenAnswer(invocation -> auth.get());
        when(authDao.insert(any(DeviceNeteaseAuthEntity.class))).thenAnswer(invocation -> { auth.set(invocation.getArgument(0)); return 1; });
        when(authDao.updateById(any(DeviceNeteaseAuthEntity.class))).thenAnswer(invocation -> { auth.set(invocation.getArgument(0)); return 1; });
        when(authDao.invalidateIfVersionMatches(any(), any())).thenAnswer(invocation -> {
            DeviceNeteaseAuthEntity value = auth.get();
            if (value == null || !"LOGGED_IN".equals(value.getStatus())
                    || !invocation.getArgument(1).equals(value.getVersion())) return 0;
            value.setCredentialCiphertext(null);
            value.setStatus("REVOKED");
            value.setVersion(value.getVersion() + 1);
            return 1;
        });
        when(sessionDao.selectOne(any())).thenAnswer(invocation -> session.get() != null
                && session.get().getActiveSlot() != null ? session.get() : null);
        when(sessionDao.selectById(any())).thenAnswer(invocation -> {
            DeviceNeteaseSessionEntity value = session.get();
            return value != null && value.getSessionId().equals(invocation.getArgument(0)) ? value : null;
        });
        when(sessionDao.insert(any(DeviceNeteaseSessionEntity.class))).thenAnswer(invocation -> { session.set(invocation.getArgument(0)); return 1; });
        when(sessionDao.updateById(any(DeviceNeteaseSessionEntity.class))).thenAnswer(invocation -> { session.set(invocation.getArgument(0)); return 1; });
        when(sessionDao.setQrDataIfActive(any(), any(), any(), any(), any())).thenReturn(1);
        when(sessionDao.updateActiveStatus(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (session.get().getActiveSlot() == null) return 0;
            String current = session.get().getStatus();
            String requested = invocation.getArgument(2);
            boolean allowed = "SCANNED".equals(requested)
                    ? "WAITING_SCAN".equals(current) || "SCANNED".equals(current)
                    : "WAITING_SCAN".equals(current) && "WAITING_SCAN".equals(requested);
            if (!allowed) return 0;
            session.get().setStatus(requested);
            return 1;
        });
        when(sessionDao.finishIfActive(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            if (session.get().getActiveSlot() == null) return 0;
            session.get().setStatus(invocation.getArgument(2));
            session.get().setFailureReason(invocation.getArgument(3));
            session.get().setActiveSlot(null);
            session.get().setQrKeyCiphertext(null);
            session.get().setQrContent(null);
            return 1;
        });
        when(sessionDao.terminateAllActive(any(), any(), any())).thenAnswer(invocation -> {
            if (session.get() == null || session.get().getActiveSlot() == null) return 0;
            session.get().setStatus("FAILED");
            session.get().setFailureReason(invocation.getArgument(1));
            session.get().setActiveSlot(null);
            session.get().setQrKeyCiphertext(null);
            session.get().setQrContent(null);
            return 1;
        });
        when(cipher.encrypt(any())).thenAnswer(invocation -> "enc:" + invocation.getArgument(0));
        when(cipher.decrypt(any())).thenAnswer(invocation -> {
            String ciphertext = invocation.getArgument(0, String.class);
            return ciphertext.substring(4);
        });
        when(completionService.complete(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            if (session.get().getActiveSlot() == null) return false;
            session.get().setStatus("LOGGED_IN");
            session.get().setActiveSlot(null);
            session.get().setQrKeyCiphertext(null);
            session.get().setQrContent(null);
            DeviceNeteaseAuthEntity value = new DeviceNeteaseAuthEntity();
            value.setDeviceId(DEVICE);
            value.setStatus("LOGGED_IN");
            value.setCredentialCiphertext("enc:" + invocation.getArgument(2));
            auth.set(value);
            return true;
        });
        when(reservationService.reserve(any())).thenAnswer(invocation -> {
            DeviceNeteaseSessionEntity value = session.get();
            if (value != null && value.getActiveSlot() != null) {
                return new NeteaseSessionReservationService.Reservation(value, true);
            }
            value = new DeviceNeteaseSessionEntity();
            value.setSessionId(java.util.UUID.randomUUID().toString());
            value.setDeviceId(DEVICE);
            value.setStatus("WAITING_SCAN");
            value.setExpiresAt(java.util.Date.from(java.time.Instant.now().plusSeconds(300)));
            value.setActiveSlot(DEVICE);
            session.set(value);
            return new NeteaseSessionReservationService.Reservation(value, false);
        });
        service = new DeviceNeteaseService(deviceDao, authDao, sessionDao, cipher, provider,
                completionService, reservationService);
    }

    @Test
    void createsAndReusesOneActiveSession() throws Exception {
        when(provider.createQrKey()).thenReturn(json.readTree("{\"code\":200,\"data\":{\"unikey\":\"key\"}}"));
        when(provider.createQr("key")).thenReturn(json.readTree(
                "{\"code\":200,\"data\":{\"qrimg\":\"" + VALID_PNG_DATA_URL + "\"}}"));

        var first = service.createSession(DEVICE);
        var second = service.createSession(DEVICE);

        assertEquals(first.sessionId(), second.sessionId());
        assertEquals(VALID_PNG_DATA_URL, first.qrContent());
        assertTrue(first.expiresAt().after(new java.util.Date()));
        org.mockito.Mockito.verify(reservationService, org.mockito.Mockito.times(2)).reserve(DEVICE);
    }

    @Test
    void servesOnlyTheCreatingDeviceQrImage() throws Exception {
        createSession();

        DeviceNeteaseService.QrImage image = service.qrImage(DEVICE, session.get().getSessionId());

        assertEquals("image/png", image.contentType());
        assertTrue(image.bytes().length > 8);
        assertThrows(RenException.class,
                () -> service.qrImage("other-device", session.get().getSessionId()));
    }

    @Test
    void rejectsTruncatedQrImage() throws Exception {
        when(provider.createQrKey()).thenReturn(json.readTree("{\"code\":200,\"data\":{\"unikey\":\"key\"}}"));
        when(provider.createQr("key")).thenReturn(json.readTree(
                "{\"code\":200,\"data\":{\"qrimg\":\"data:image/png;base64,iVBORw0KGgo=\"}}"));

        assertThrows(RenException.class, () -> service.createSession(DEVICE));
    }

    @Test
    void mapsWaitingScannedAndExpiredAndRejectsDeviceMismatch() throws Exception {
        createSession();
        when(provider.checkQr("key"))
                .thenReturn(json.readTree("{\"code\":801}"))
                .thenReturn(json.readTree("{\"code\":802}"))
                .thenReturn(json.readTree("{\"code\":800}"));

        assertThrows(RenException.class, () -> service.poll("other-device", session.get().getSessionId()));
        assertEquals(NeteaseLoginStatus.WAITING_SCAN, service.poll(DEVICE, session.get().getSessionId()).status());
        assertEquals(NeteaseLoginStatus.SCANNED, service.poll(DEVICE, session.get().getSessionId()).status());
        assertEquals(NeteaseLoginStatus.EXPIRED, service.poll(DEVICE, session.get().getSessionId()).status());
        assertEquals(NeteaseLoginStatus.EXPIRED, service.poll(DEVICE, session.get().getSessionId()).status());
    }

    @Test
    void bindsCredentialOnSuccessAndRepeatedPollDoesNotCallProviderAgain() throws Exception {
        createSession();
        when(provider.checkQr("key")).thenReturn(json.readTree("{\"code\":803,\"cookie\":\"MUSIC_U=secret\"}"));
        when(provider.account("MUSIC_U=secret")).thenReturn(json.readTree(
                "{\"code\":200,\"account\":{\"id\":7},\"profile\":{\"nickname\":\"n\",\"avatarUrl\":\"a\"}}"));

        assertEquals(NeteaseLoginStatus.LOGGED_IN, service.poll(DEVICE, session.get().getSessionId()).status());
        assertEquals(NeteaseLoginStatus.LOGGED_IN, service.poll(DEVICE, session.get().getSessionId()).status());
        assertEquals("enc:MUSIC_U=secret", auth.get().getCredentialCiphertext());
        assertNull(session.get().getQrKeyCiphertext());
    }

    @Test
    void logoutAlwaysRevokesLocallyAndRepeatedLogoutIsExplicit() throws Exception {
        loggedInAuth();
        when(provider.logout("cookie")).thenThrow(new RenException("provider down"));

        var first = service.logout(DEVICE);
        var second = service.logout(DEVICE);

        assertTrue(first.providerFailure());
        assertEquals("LOGGED_OUT", first.outcome());
        assertEquals("ALREADY_LOGGED_OUT", second.outcome());
        assertNull(auth.get().getCredentialCiphertext());
    }

    @Test
    void internalEndpointOnlyReturnsCredentialForValidLoginAndAdminCanRevoke() {
        assertFalse(service.internalAuth(DEVICE).authorized());
        assertNull(service.internalAuth(DEVICE).internalCredential());
        loggedInAuth();
        assertEquals("cookie", service.internalAuth(DEVICE).internalCredential());
        assertEquals(0, service.internalAuth(DEVICE).credentialVersion());

        assertEquals("REVOKED", service.revoke(DEVICE, "设备转让").outcome());
        assertEquals("ADMIN_REVOKED", auth.get().getRevokeReason());
        assertFalse(service.internalAuth(DEVICE).authorized());
        assertNull(service.internalAuth(DEVICE).internalCredential());
    }

    @Test
    void providerCreationFailurePersistsFailedReservation() {
        when(provider.createQrKey()).thenThrow(new RenException("provider down"));

        assertThrows(RenException.class, () -> service.createSession(DEVICE));
        assertEquals("FAILED", session.get().getStatus());
        assertEquals("PROVIDER_CREATE_FAILED", session.get().getFailureReason());
        assertNull(session.get().getActiveSlot());
    }

    @Test
    void credentialInvalidationUsesVersionCompareAndSet() {
        loggedInAuth();

        assertFalse(service.invalidate(DEVICE, 99).invalidated());
        assertTrue(service.invalidate(DEVICE, 0).invalidated());
        assertNull(auth.get().getCredentialCiphertext());
        assertFalse(service.invalidate(DEVICE, 0).invalidated());
    }

    @Test
    void logoutTerminatesActiveQrSessionAndAdminReasonIsNeverEchoed() throws Exception {
        createSession();
        var response = service.revoke(DEVICE, "用户输入的敏感原因");

        assertEquals("ALREADY_LOGGED_OUT", response.outcome());
        assertEquals("ADMIN_REVOKED", response.reason());
        assertNull(session.get().getActiveSlot());
        assertNull(session.get().getQrKeyCiphertext());
        assertNull(session.get().getQrContent());
    }

    @Test
    void rejectedCompletionCannotBindAfterSessionWasRevoked() throws Exception {
        createSession();
        when(provider.checkQr("key")).thenReturn(json.readTree("{\"code\":803,\"cookie\":\"secret\"}"));
        when(provider.account("secret")).thenReturn(json.readTree(
                "{\"code\":200,\"account\":{\"id\":7},\"profile\":{}}"));
        service.revoke(DEVICE, "撤销");

        assertEquals(NeteaseLoginStatus.FAILED, service.poll(DEVICE, session.get().getSessionId()).status());
        assertNull(auth.get());
    }

    @Test
    void statusUpdateCannotOverwriteTerminalState() throws Exception {
        createSession();
        when(provider.checkQr("key")).thenReturn(json.readTree("{\"code\":801}"));
        when(sessionDao.updateActiveStatus(any(), any(), any(), any())).thenAnswer(invocation -> {
            session.get().setStatus("FAILED");
            session.get().setFailureReason("ADMIN_REVOKED");
            session.get().setActiveSlot(null);
            return 0;
        });

        assertEquals(NeteaseLoginStatus.FAILED, service.poll(DEVICE, session.get().getSessionId()).status());
    }

    @Test
    void waitingResponseCannotMoveScannedSessionBackwards() throws Exception {
        createSession();
        when(provider.checkQr("key"))
                .thenReturn(json.readTree("{\"code\":802}"))
                .thenReturn(json.readTree("{\"code\":801}"));

        assertEquals(NeteaseLoginStatus.SCANNED, service.poll(DEVICE, session.get().getSessionId()).status());
        assertEquals(NeteaseLoginStatus.SCANNED, service.poll(DEVICE, session.get().getSessionId()).status());
    }

    @Test
    void temporaryPollingFailureKeepsQrSessionActive() throws Exception {
        createSession();
        when(provider.checkQr("key")).thenThrow(
                new NeteaseProviderUnavailableException("temporary", new RuntimeException("network")));

        assertThrows(NeteaseProviderUnavailableException.class,
                () -> service.poll(DEVICE, session.get().getSessionId()));
        assertEquals("WAITING_SCAN", session.get().getStatus());
        assertEquals(DEVICE, session.get().getActiveSlot());
        assertTrue(session.get().getQrContent() != null);
    }

    @Test
    void expiryBetween803AndDatabaseClaimDoesNotBindCredential() throws Exception {
        createSession();
        when(provider.checkQr("key")).thenReturn(json.readTree("{\"code\":803,\"cookie\":\"secret\"}"));
        when(provider.account("secret")).thenReturn(json.readTree(
                "{\"code\":200,\"account\":{\"id\":7},\"profile\":{}}"));
        org.mockito.Mockito.doAnswer(invocation -> {
            session.get().setStatus("EXPIRED");
            session.get().setActiveSlot(null);
            return false;
        }).when(completionService).complete(any(), any(), any(), any(), any(), any());

        assertEquals(NeteaseLoginStatus.EXPIRED, service.poll(DEVICE, session.get().getSessionId()).status());
        assertNull(auth.get());
    }

    private void createSession() throws Exception {
        when(provider.createQrKey()).thenReturn(json.readTree("{\"code\":200,\"data\":{\"unikey\":\"key\"}}"));
        when(provider.createQr("key")).thenReturn(json.readTree(
                "{\"code\":200,\"data\":{\"qrimg\":\"" + VALID_PNG_DATA_URL + "\"}}"));
        service.createSession(DEVICE);
    }

    private void loggedInAuth() {
        DeviceNeteaseAuthEntity value = new DeviceNeteaseAuthEntity();
        value.setDeviceId(DEVICE);
        value.setStatus("LOGGED_IN");
        value.setCredentialCiphertext("enc:cookie");
        value.setVersion(0);
        auth.set(value);
    }

}
