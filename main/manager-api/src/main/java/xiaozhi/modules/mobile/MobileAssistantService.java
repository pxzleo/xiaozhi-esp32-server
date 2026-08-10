package xiaozhi.modules.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;

import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.mobile.MobileAssistantDTOs.AuthorizeRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.AuthorizeResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.BindRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.BindResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MessageClaimResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MessageActionResponse;
import xiaozhi.modules.sys.service.SysParamsService;

@Service
public class MobileAssistantService {
    static final int PROTOCOL_VERSION = 1;
    static final Set<String> ALLOWED_CAPABILITIES = Set.of("text_chat", "voice_session", "notification_gateway");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MobileInstanceDao mobileDao;
    private final DeviceDao deviceDao;
    private final AgentService agentService;
    private final SysParamsService sysParamsService;
    private final MobileMessageReceiptDao receiptDao;

    public MobileAssistantService(MobileInstanceDao mobileDao, DeviceDao deviceDao,
            AgentService agentService, SysParamsService sysParamsService,
            MobileMessageReceiptDao receiptDao) {
        this.mobileDao = mobileDao;
        this.deviceDao = deviceDao;
        this.agentService = agentService;
        this.sysParamsService = sysParamsService;
        this.receiptDao = receiptDao;
    }

    @Transactional(rollbackFor = Exception.class)
    public BindResponse bind(Long userId, BindRequest request) {
        requireVersionAndCapabilities(request.version(), request.capabilities());
        if (userId == null || !agentService.checkAgentPermission(request.agentId(), userId)) {
            throw new MobileApiException(HttpStatus.FORBIDDEN, "AGENT_FORBIDDEN", "无权绑定到该智能体");
        }

        String token = newCredential();
        Date now = new Date();
        MobileInstanceEntity entity = mobileDao.selectActiveForUpdate(userId, request.installationId());
        if (entity == null) {
            entity = new MobileInstanceEntity();
            String stableBindingHash = hashCredential(userId + "|" + request.installationId());
            entity.setMobileInstanceId("mob_" + stableBindingHash.substring(0, 32));
            entity.setDeviceId(stableBindingHash.substring(32));
            entity.setUserId(userId);
            entity.setInstallationId(request.installationId());
            entity.setCreatedAt(now);
            populateMutable(entity, request, token, now);
            mobileDao.insertDeviceIgnore(entity);
            if (mobileDao.insertIgnore(entity) == 0) {
                throw new MobileApiException(HttpStatus.CONFLICT, "BIND_CONFLICT_RETRY", "并发绑定冲突，请重试");
            }
        } else {
            populateMutable(entity, request, token, now);
            mobileDao.updateById(entity);
            DeviceEntity device = new DeviceEntity();
            device.setId(entity.getDeviceId());
            device.setAgentId(request.agentId());
            device.setAppVersion(request.appVersion());
            device.setUpdater(userId);
            device.setUpdateDate(now);
            deviceDao.updateById(device);
        }

        String websocketUrl = sysParamsService.getValue("server.websocket", true);
        if (StringUtils.isBlank(websocketUrl) || "null".equalsIgnoreCase(websocketUrl)) {
            throw new MobileApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WEBSOCKET_NOT_CONFIGURED", "WebSocket 地址未配置");
        }
        websocketUrl = mobileWebsocketUrl(websocketUrl.split(";", 2)[0]);
        return new BindResponse(PROTOCOL_VERSION, entity.getMobileInstanceId(), token,
                entity.getCredentialVersion(), websocketUrl, "/mobile/assistant");
    }

    @Transactional(rollbackFor = Exception.class)
    public AuthorizeResponse authorize(String instanceId, AuthorizeRequest request) {
        if (request.version() != PROTOCOL_VERSION || !validCapabilities(request.capabilities())) {
            return AuthorizeResponse.denied();
        }
        MobileInstanceEntity entity = mobileDao.selectByIdForUpdate(instanceId);
        if (entity == null || entity.getRevokedAt() != null
                || !Integer.valueOf(request.credentialVersion()).equals(entity.getCredentialVersion())
                || !MessageDigest.isEqual(hashCredential(request.token()).getBytes(StandardCharsets.US_ASCII),
                        StringUtils.defaultString(entity.getCredentialHash()).getBytes(StandardCharsets.US_ASCII))
                || !request.installationId().equals(entity.getInstallationId())
                || !storedCapabilities(entity).containsAll(request.capabilities())) {
            return AuthorizeResponse.denied();
        }
        MobileInstanceEntity touched = new MobileInstanceEntity();
        touched.setMobileInstanceId(instanceId);
        touched.setLastConnectedAt(new Date());
        touched.setUpdatedAt(new Date());
        mobileDao.updateById(touched);
        return new AuthorizeResponse(true, instanceId, entity.getAgentId(), entity.getCredentialVersion());
    }

    @Transactional(rollbackFor = Exception.class)
    public MessageClaimResponse claimMessage(String instanceId, String messageId) {
        MobileInstanceEntity entity = mobileDao.selectByIdForUpdate(instanceId);
        if (entity == null || entity.getRevokedAt() != null) {
            return new MessageClaimResponse("revoked", null);
        }
        receiptDao.deleteExpired(instanceId);
        MobileMessageReceiptEntity existing = receiptDao.selectForUpdate(instanceId, messageId);
        if (existing != null && "ACCEPTED".equals(existing.getStatus())) {
            return new MessageClaimResponse("duplicate", null);
        }
        String claimToken = UUID.randomUUID().toString().replace("-", "");
        if (existing == null) {
            receiptDao.insertClaim(instanceId, messageId, claimToken);
        } else {
            if (receiptDao.reclaim(instanceId, messageId, claimToken) != 1) {
                return new MessageClaimResponse("busy", null);
            }
        }
        return new MessageClaimResponse("claimed", claimToken);
    }

    @Transactional(rollbackFor = Exception.class)
    public MessageActionResponse completeMessage(String instanceId, String messageId, String claimToken) {
        if (receiptDao.complete(instanceId, messageId, claimToken) == 1) {
            return new MessageActionResponse(true);
        }
        MobileMessageReceiptEntity existing = receiptDao.selectForUpdate(instanceId, messageId);
        return new MessageActionResponse(existing != null && "ACCEPTED".equals(existing.getStatus())
                && MessageDigest.isEqual(claimToken.getBytes(StandardCharsets.US_ASCII),
                        StringUtils.defaultString(existing.getClaimToken()).getBytes(StandardCharsets.US_ASCII)));
    }

    public MessageActionResponse renewMessage(String instanceId, String messageId, String claimToken) {
        return new MessageActionResponse(receiptDao.renew(instanceId, messageId, claimToken) == 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long userId, String instanceId, int credentialVersion) {
        MobileInstanceEntity entity = mobileDao.selectByIdForUpdate(instanceId);
        if (entity == null || userId == null || !userId.equals(entity.getUserId())) {
            throw new MobileApiException(HttpStatus.NOT_FOUND, "MOBILE_INSTANCE_NOT_FOUND", "手机实例不存在");
        }
        if (!Integer.valueOf(credentialVersion).equals(entity.getCredentialVersion())) {
            throw new MobileApiException(HttpStatus.CONFLICT, "CREDENTIAL_VERSION_CONFLICT", "手机凭据版本已变化");
        }
        if (entity.getRevokedAt() == null) {
            Date now = new Date();
            entity.setCredentialHash(null);
            entity.setRevokedAt(now);
            entity.setUpdatedAt(now);
            mobileDao.updateById(entity);
        }
    }

    private void populateMutable(MobileInstanceEntity entity, BindRequest request, String token, Date now) {
        entity.setAgentId(request.agentId());
        entity.setPlatform(request.platform());
        entity.setAppVersion(request.appVersion());
        entity.setCapabilities(String.join(",", new HashSet<>(request.capabilities())));
        entity.setCredentialHash(hashCredential(token));
        entity.setCredentialVersion(entity.getCredentialVersion() == null ? 1 : entity.getCredentialVersion() + 1);
        entity.setUpdatedAt(now);
        entity.setRevokedAt(null);
    }

    private void requireVersionAndCapabilities(int version, List<String> capabilities) {
        if (version != PROTOCOL_VERSION) {
            throw new MobileApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_VERSION", "不支持的手机协议版本");
        }
        if (!validCapabilities(capabilities)) {
            throw new MobileApiException(HttpStatus.BAD_REQUEST, "INVALID_CAPABILITIES", "手机能力集合无效");
        }
    }

    private boolean validCapabilities(List<String> capabilities) {
        return capabilities != null && !capabilities.isEmpty()
                && capabilities.size() == new HashSet<>(capabilities).size()
                && ALLOWED_CAPABILITIES.containsAll(capabilities);
    }

    private Set<String> storedCapabilities(MobileInstanceEntity entity) {
        return Set.of(StringUtils.defaultString(entity.getCapabilities()).split(","));
    }

    private static String newCredential() {
        byte[] value = new byte[32];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String mobileWebsocketUrl(String configuredUrl) {
        try {
            URI configured = new URI(configuredUrl);
            if (!("ws".equals(configured.getScheme()) || "wss".equals(configured.getScheme()))
                    || configured.getHost() == null || configured.getUserInfo() != null) {
                throw new URISyntaxException(configuredUrl, "WebSocket 地址无效");
            }
            return new URI(configured.getScheme(), null, configured.getHost(), configured.getPort(),
                    "/mobile/assistant", null, null).toString();
        } catch (URISyntaxException exception) {
            throw new MobileApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "WEBSOCKET_NOT_CONFIGURED", "WebSocket 地址无效");
        }
    }

    static String hashCredential(String credential) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }
}
