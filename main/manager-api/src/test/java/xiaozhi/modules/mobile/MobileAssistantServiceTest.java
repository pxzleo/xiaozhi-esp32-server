package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.mobile.MobileAssistantDTOs.AuthorizeRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.BindRequest;
import xiaozhi.modules.sys.service.SysParamsService;

class MobileAssistantServiceTest {
    private MobileInstanceDao mobileDao;
    private DeviceDao deviceDao;
    private AgentService agentService;
    private MobileAssistantService service;
    private MobileMessageReceiptDao receiptDao;

    @BeforeEach
    void setUp() {
        mobileDao = mock(MobileInstanceDao.class);
        deviceDao = mock(DeviceDao.class);
        agentService = mock(AgentService.class);
        SysParamsService params = mock(SysParamsService.class);
        receiptDao = mock(MobileMessageReceiptDao.class);
        when(agentService.checkAgentPermission("agent-1", 7L)).thenReturn(true);
        when(params.getValue("server.websocket", true)).thenReturn("ws://127.0.0.1:18000/xiaozhi/v1/");
        when(mobileDao.insertIgnore(any(MobileInstanceEntity.class))).thenReturn(1);
        service = new MobileAssistantService(mobileDao, deviceDao, agentService, params, receiptDao);
    }

    @Test
    void bindCreatesRevocableCredentialWithoutHardwareIdentifier() {
        var result = service.bind(7L, request());

        assertTrue(result.mobileInstanceId().startsWith("mob_"));
        assertEquals("/mobile/assistant", result.websocketPath());
        assertEquals("ws://127.0.0.1:18000/mobile/assistant", result.websocketUrl());
        assertFalse(result.accessToken().isBlank());
        assertEquals(1, result.credentialVersion());
        org.mockito.Mockito.verify(mobileDao).insertDeviceIgnore(any(MobileInstanceEntity.class));
        org.mockito.Mockito.verify(mobileDao).insertIgnore(any(MobileInstanceEntity.class));
    }

    @Test
    void rejectsUnknownVersionCapabilityAndForeignAgent() {
        assertThrows(MobileApiException.class, () -> service.bind(7L,
                new BindRequest(2, request().installationId(), "android", "0.1.0", "agent-1",
                        List.of("text_chat"))));
        assertThrows(MobileApiException.class, () -> service.bind(7L,
                new BindRequest(1, request().installationId(), "android", "0.1.0", "agent-1",
                        List.of("client_tts_text"))));
        assertThrows(MobileApiException.class, () -> service.bind(8L, request()));
    }

    @Test
    void concurrentFirstBindReturnsControlledConflictWithoutRotatingWinner() {
        when(mobileDao.insertIgnore(any(MobileInstanceEntity.class))).thenReturn(0);

        MobileApiException exception = assertThrows(MobileApiException.class,
                () -> service.bind(7L, request()));

        assertEquals("BIND_CONFLICT_RETRY", exception.getErrorCode());
        assertEquals(409, exception.getStatus().value());
    }

    @Test
    void authorizationChecksInstanceTokenInstallationAndCapabilities() {
        MobileInstanceEntity entity = new MobileInstanceEntity();
        entity.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        entity.setInstallationId(request().installationId());
        entity.setCapabilities("text_chat,voice_session");
        entity.setCredentialHash(MobileAssistantService.hashCredential("secret"));
        entity.setCredentialVersion(3);
        when(mobileDao.selectByIdForUpdate(entity.getMobileInstanceId())).thenReturn(entity);

        assertTrue(service.authorize(entity.getMobileInstanceId(),
                new AuthorizeRequest(1, 3, request().installationId(), "secret",
                        List.of("text_chat", "voice_session"))).authorized());
        assertFalse(service.authorize(entity.getMobileInstanceId(),
                new AuthorizeRequest(1, 3, request().installationId(), "wrong", List.of("text_chat"))).authorized());
        assertFalse(service.authorize(entity.getMobileInstanceId(),
                new AuthorizeRequest(1, 2, request().installationId(), "secret", List.of("text_chat"))).authorized());
    }

    @Test
    void messageClaimIsDurableAndDuplicateIsExplicit() {
        MobileInstanceEntity entity = new MobileInstanceEntity();
        entity.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        when(mobileDao.selectByIdForUpdate(entity.getMobileInstanceId())).thenReturn(entity);
        MobileMessageReceiptEntity accepted = new MobileMessageReceiptEntity();
        accepted.setStatus("ACCEPTED");
        when(receiptDao.selectForUpdate(entity.getMobileInstanceId(), "msg_01"))
                .thenReturn(null, accepted);
        when(receiptDao.insertClaim(
                org.mockito.ArgumentMatchers.eq(entity.getMobileInstanceId()),
                org.mockito.ArgumentMatchers.eq("msg_01"),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(1);

        assertEquals("claimed", service.claimMessage(entity.getMobileInstanceId(), "msg_01").status());
        assertEquals("duplicate", service.claimMessage(entity.getMobileInstanceId(), "msg_01").status());
    }

    @Test
    void leaseRenewalAndCompletionAreBoundToExactClaimToken() {
        String instanceId = "mob_0123456789abcdef0123456789abcdef";
        String currentToken = "a".repeat(32);
        String staleToken = "b".repeat(32);
        MobileMessageReceiptEntity accepted = new MobileMessageReceiptEntity();
        accepted.setStatus("ACCEPTED");
        accepted.setClaimToken(currentToken);
        when(receiptDao.renew(instanceId, "msg_01", currentToken)).thenReturn(1);
        when(receiptDao.selectForUpdate(instanceId, "msg_01")).thenReturn(accepted);

        assertTrue(service.renewMessage(instanceId, "msg_01", currentToken).completed());
        assertTrue(service.completeMessage(instanceId, "msg_01", currentToken).completed());
        assertFalse(service.completeMessage(instanceId, "msg_01", staleToken).completed());
    }

    @Test
    void staleCredentialVersionCannotRevokeRotatedCredential() {
        MobileInstanceEntity entity = new MobileInstanceEntity();
        entity.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        entity.setUserId(7L);
        entity.setCredentialVersion(4);
        when(mobileDao.selectByIdForUpdate(entity.getMobileInstanceId())).thenReturn(entity);

        MobileApiException exception = assertThrows(MobileApiException.class,
                () -> service.revoke(7L, entity.getMobileInstanceId(), 3));

        assertEquals("CREDENTIAL_VERSION_CONFLICT", exception.getErrorCode());
        org.mockito.Mockito.verify(mobileDao, org.mockito.Mockito.never()).updateById(entity);
    }

    private BindRequest request() {
        return new BindRequest(1, "123e4567-e89b-12d3-a456-426614174000", "android", "0.1.0", "agent-1",
                List.of("text_chat", "voice_session"));
    }
}
