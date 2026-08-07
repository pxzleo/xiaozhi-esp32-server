package xiaozhi.modules.device.netease;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

@Service
public class DeviceNeteaseService {
    private static final int POLL_INTERVAL_SECONDS = 2;
    private static final int MAX_QR_IMAGE_BYTES = 256 * 1024;

    private final DeviceDao deviceDao;
    private final DeviceNeteaseAuthDao authDao;
    private final DeviceNeteaseSessionDao sessionDao;
    private final NeteaseCredentialCipher cipher;
    private final NeteaseProviderClient provider;
    private final NeteaseLoginCompletionService completionService;
    private final NeteaseSessionReservationService reservationService;

    public DeviceNeteaseService(DeviceDao deviceDao, DeviceNeteaseAuthDao authDao,
            DeviceNeteaseSessionDao sessionDao, NeteaseCredentialCipher cipher,
            NeteaseProviderClient provider, NeteaseLoginCompletionService completionService,
            NeteaseSessionReservationService reservationService) {
        this.deviceDao = deviceDao;
        this.authDao = authDao;
        this.sessionDao = sessionDao;
        this.cipher = cipher;
        this.provider = provider;
        this.completionService = completionService;
        this.reservationService = reservationService;
    }

    public record StatusResponse(NeteaseLoginStatus status, String sessionId, Date expiresAt,
            String providerUserId, String nickname, String avatarUrl, String reason) {}
    public record SessionResponse(String sessionId, String qrContent, Date expiresAt,
            int pollIntervalSeconds) {}
    public record QrImage(String contentType, byte[] bytes) {}
    public record LogoutResponse(String outcome, boolean providerFailure, String reason) {}
    public record InternalAuthResponse(boolean authorized, String providerUserId, String nickname,
            String avatarUrl, String internalCredential, Integer credentialVersion) {}
    public record InvalidateResponse(boolean invalidated) {}

    public StatusResponse status(String deviceId) {
        deviceId = canonicalDeviceId(deviceId);
        DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
        if (auth != null && "LOGGED_IN".equals(auth.getStatus())) return authStatus(auth);
        DeviceNeteaseSessionEntity session = latestSession(deviceId);
        if (session != null && isActive(session) && session.getExpiresAt().before(new Date())) {
            finishSession(session, NeteaseLoginStatus.EXPIRED, null);
        }
        if (session != null && (isActive(session) || "EXPIRED".equals(session.getStatus())
                || "FAILED".equals(session.getStatus()))) {
            return sessionStatus(session);
        }
        if (auth != null && "PROVIDER_LOGOUT_FAILED".equals(auth.getStatus())) {
            return new StatusResponse(NeteaseLoginStatus.FAILED, null, null, null, null, null,
                    safeReason(auth.getRevokeReason()));
        }
        return new StatusResponse(NeteaseLoginStatus.NOT_LOGGED_IN, null, null, null, null, null,
                auth == null ? null : safeReason(auth.getRevokeReason()));
    }

    public SessionResponse createSession(String deviceId) {
        NeteaseSessionReservationService.Reservation reservation = reservationService.reserve(deviceId);
        DeviceNeteaseSessionEntity session = reservation.session();
        deviceId = session.getDeviceId();
        if (reservation.existing()) return reusable(session);

        try {
            JsonNode keyPayload = provider.createQrKey();
            if (keyPayload.path("code").asInt(-1) != 200) throw new RenException("网易云二维码密钥创建失败");
            String key = keyPayload.path("data").path("unikey").asText();
            if (StringUtils.isBlank(key)) throw new RenException("网易云服务未返回二维码密钥");
            JsonNode qrPayload = provider.createQr(key);
            if (qrPayload.path("code").asInt(-1) != 200) throw new RenException("网易云二维码创建失败");
            String qrContent = qrPayload.path("data").path("qrimg").asText();
            decodeQrImage(qrContent);
            String encryptedKey = cipher.encrypt(key);
            if (sessionDao.setQrDataIfActive(session.getSessionId(), deviceId, encryptedKey,
                    qrContent, new Date()) != 1) {
                throw new RenException("设备登录会话已终止");
            }
            session.setQrKeyCiphertext(encryptedKey);
            session.setQrContent(qrContent);
            return reusable(session);
        } catch (RuntimeException exception) {
            finishSession(session, NeteaseLoginStatus.FAILED, "PROVIDER_CREATE_FAILED");
            throw new RenException("网易云登录会话创建失败", exception);
        }
    }

    public StatusResponse poll(String deviceId, String sessionId) {
        deviceId = canonicalDeviceId(deviceId);
        DeviceNeteaseSessionEntity session = sessionDao.selectById(sessionId);
        if (session == null) throw new RenException("登录会话不存在");
        if (!deviceId.equals(session.getDeviceId())) throw new RenException("登录会话与设备不匹配");
        if (!isActive(session)) {
            if (NeteaseLoginStatus.LOGGED_IN.name().equals(session.getStatus())) {
                DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
                if (auth != null && "LOGGED_IN".equals(auth.getStatus())) return authStatus(auth);
            }
            return sessionStatus(session);
        }
        if (session.getExpiresAt().before(new Date())) {
            finishSession(session, NeteaseLoginStatus.EXPIRED, null);
            return sessionStatus(session);
        }
        try {
            String key = cipher.decrypt(session.getQrKeyCiphertext());
            JsonNode check = provider.checkQr(key);
            int code = check.path("code").asInt(-1);
            if (code == 801) return updateSessionStatus(session, NeteaseLoginStatus.WAITING_SCAN);
            if (code == 802) return updateSessionStatus(session, NeteaseLoginStatus.SCANNED);
            if (code == 800) {
                finishSession(session, NeteaseLoginStatus.EXPIRED, null);
                return sessionStatus(session);
            }
            if (code != 803) throw new RenException("网易云登录服务返回未知状态");
            String cookie = check.path("cookie").asText();
            if (StringUtils.isBlank(cookie)) throw new RenException("网易云登录成功响应缺少凭证");
            JsonNode accountPayload = provider.account(cookie);
            if (accountPayload.path("code").asInt(-1) != 200) throw new RenException("网易云账号读取失败");
            JsonNode account = accountPayload.path("account");
            JsonNode profile = accountPayload.path("profile");
            String providerUserId = account.path("id").asText();
            if (StringUtils.isBlank(providerUserId)) providerUserId = profile.path("userId").asText();
            if (StringUtils.isBlank(providerUserId)) throw new RenException("网易云账号响应缺少用户标识");
            boolean completed = completionService.complete(sessionId, deviceId, cookie, providerUserId,
                    profile.path("nickname").asText(null), profile.path("avatarUrl").asText(null));
            if (!completed) return statusAfterRejectedTransition(deviceId, sessionId);
            return authStatus(authDao.selectById(deviceId));
        } catch (NeteaseProviderUnavailableException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            finishSession(session, NeteaseLoginStatus.FAILED, "PROVIDER_POLL_FAILED");
            return statusAfterRejectedTransition(deviceId, sessionId);
        }
    }

    public QrImage qrImage(String deviceId, String sessionId) {
        deviceId = canonicalDeviceId(deviceId);
        DeviceNeteaseSessionEntity session = sessionDao.selectById(sessionId);
        if (session == null) throw new RenException("登录会话不存在");
        if (!deviceId.equals(session.getDeviceId())) throw new RenException("登录会话与设备不匹配");
        if (!isActive(session) || session.getExpiresAt().before(new Date())) {
            if (isActive(session)) finishSession(session, NeteaseLoginStatus.EXPIRED, null);
            throw new RenException("登录会话已结束");
        }
        return decodeQrImage(session.getQrContent());
    }

    public LogoutResponse logout(String deviceId) {
        deviceId = canonicalDeviceId(deviceId);
        terminateActiveSessions(deviceId, "USER_LOGOUT");
        DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
        if (auth == null || !"LOGGED_IN".equals(auth.getStatus())) {
            return new LogoutResponse("ALREADY_LOGGED_OUT", false,
                    auth == null ? null : safeReason(auth.getRevokeReason()));
        }
        boolean providerFailure = false;
        try {
            JsonNode response = provider.logout(cipher.decrypt(auth.getCredentialCiphertext()));
            int code = response.path("code").asInt(-1);
            providerFailure = code != 200;
        } catch (RuntimeException exception) {
            providerFailure = true;
        }
        revokeAuth(auth, providerFailure ? "PROVIDER_LOGOUT_FAILED" : "USER_LOGOUT",
                providerFailure ? "PROVIDER_LOGOUT_FAILED" : "REVOKED");
        return new LogoutResponse("LOGGED_OUT", providerFailure,
                providerFailure ? "PROVIDER_LOGOUT_FAILED" : "USER_LOGOUT");
    }

    public LogoutResponse revoke(String deviceId, String reason) {
        deviceId = canonicalDeviceId(deviceId);
        terminateActiveSessions(deviceId, "ADMIN_REVOKED");
        DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
        if (auth == null || !"LOGGED_IN".equals(auth.getStatus())) {
            return new LogoutResponse("ALREADY_LOGGED_OUT", false, "ADMIN_REVOKED");
        }
        revokeAuth(auth, "ADMIN_REVOKED", "REVOKED");
        return new LogoutResponse("REVOKED", false, "ADMIN_REVOKED");
    }

    public void revokeForDeviceRemoval(String macAddress) {
        if (StringUtils.isBlank(macAddress)) return;
        terminateActiveSessions(macAddress, "DEVICE_REMOVED");
        DeviceNeteaseAuthEntity auth = authDao.selectById(macAddress);
        if (auth != null && "LOGGED_IN".equals(auth.getStatus())) {
            revokeAuth(auth, "DEVICE_REMOVED", "REVOKED");
        }
    }

    public InternalAuthResponse internalAuth(String macAddress) {
        macAddress = canonicalDeviceId(macAddress);
        DeviceNeteaseAuthEntity auth = authDao.selectById(macAddress);
        if (auth == null || !"LOGGED_IN".equals(auth.getStatus())) {
            return new InternalAuthResponse(false, null, null, null, null, null);
        }
        return new InternalAuthResponse(true, auth.getProviderUserId(), auth.getNickname(), auth.getAvatarUrl(),
                cipher.decrypt(auth.getCredentialCiphertext()), auth.getVersion());
    }

    public InvalidateResponse invalidate(String macAddress, Integer credentialVersion) {
        macAddress = canonicalDeviceId(macAddress);
        boolean invalidated = authDao.invalidateIfVersionMatches(macAddress, credentialVersion) == 1;
        return new InvalidateResponse(invalidated);
    }

    private String canonicalDeviceId(String identifier) {
        DeviceEntity device = deviceDao.selectById(identifier);
        if (device == null) {
            device = deviceDao.selectOne(new LambdaQueryWrapper<DeviceEntity>()
                    .eq(DeviceEntity::getMacAddress, identifier).last("LIMIT 1"));
        }
        if (device == null || device.getUserId() == null) throw new RenException("设备不存在或尚未绑定");
        return StringUtils.defaultIfBlank(device.getMacAddress(), device.getId());
    }

    private DeviceNeteaseSessionEntity latestSession(String deviceId) {
        return sessionDao.selectOne(new LambdaQueryWrapper<DeviceNeteaseSessionEntity>()
                .eq(DeviceNeteaseSessionEntity::getDeviceId, deviceId)
                .orderByDesc(DeviceNeteaseSessionEntity::getCreatedAt).last("LIMIT 1"));
    }

    private SessionResponse reusable(DeviceNeteaseSessionEntity session) {
        if (StringUtils.isBlank(session.getQrContent())) throw new RenException("设备登录会话正在创建，请稍后重试");
        return new SessionResponse(session.getSessionId(), session.getQrContent(), session.getExpiresAt(),
                POLL_INTERVAL_SECONDS);
    }

    private boolean isActive(DeviceNeteaseSessionEntity session) {
        return session.getActiveSlot() != null;
    }

    private StatusResponse updateSessionStatus(DeviceNeteaseSessionEntity session, NeteaseLoginStatus status) {
        int updated = sessionDao.updateActiveStatus(session.getSessionId(), session.getDeviceId(),
                status.name(), new Date());
        if (updated == 1) {
            session.setStatus(status.name());
            return sessionStatus(session);
        }
        return statusAfterRejectedTransition(session.getDeviceId(), session.getSessionId());
    }

    private void finishSession(DeviceNeteaseSessionEntity session, NeteaseLoginStatus status, String reason) {
        int updated = sessionDao.finishIfActive(session.getSessionId(), session.getDeviceId(),
                status.name(), reason, new Date());
        if (updated == 1) {
            session.setStatus(status.name());
            session.setFailureReason(reason);
            session.setActiveSlot(null);
            session.setQrKeyCiphertext(null);
            session.setQrContent(null);
        }
    }

    private StatusResponse statusAfterRejectedTransition(String deviceId, String sessionId) {
        DeviceNeteaseSessionEntity current = sessionDao.selectById(sessionId);
        if (current == null) throw new RenException("登录会话不存在");
        if (isActive(current) && current.getExpiresAt().before(new Date())) {
            finishSession(current, NeteaseLoginStatus.EXPIRED, null);
            current = sessionDao.selectById(sessionId);
        }
        if (NeteaseLoginStatus.LOGGED_IN.name().equals(current.getStatus())) {
            DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
            if (auth != null && "LOGGED_IN".equals(auth.getStatus())) return authStatus(auth);
        }
        return sessionStatus(current);
    }

    private void terminateActiveSessions(String deviceId, String reason) {
        sessionDao.terminateAllActive(deviceId, reason, new Date());
    }

    private String safeReason(String reason) {
        return switch (StringUtils.defaultString(reason)) {
            case "USER_LOGOUT", "PROVIDER_LOGOUT_FAILED", "ADMIN_REVOKED", "DEVICE_REMOVED",
                    "CREDENTIAL_INVALIDATED" -> reason;
            case "" -> null;
            default -> "REVOKED";
        };
    }

    private QrImage decodeQrImage(String dataUrl) {
        if (StringUtils.isBlank(dataUrl)) throw new RenException("网易云服务未返回二维码图片");
        String pngPrefix = "data:image/png;base64,";
        String jpegPrefix = "data:image/jpeg;base64,";
        String contentType;
        String encoded;
        if (dataUrl.startsWith(pngPrefix)) {
            contentType = MediaType.IMAGE_PNG_VALUE;
            encoded = dataUrl.substring(pngPrefix.length());
        } else if (dataUrl.startsWith(jpegPrefix)) {
            contentType = MediaType.IMAGE_JPEG_VALUE;
            encoded = dataUrl.substring(jpegPrefix.length());
        } else {
            throw new RenException("网易云服务返回的二维码图片格式不支持");
        }
        if (encoded.length() > (MAX_QR_IMAGE_BYTES * 4 / 3) + 4) {
            throw new RenException("网易云二维码图片大小不符合要求");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length == 0 || bytes.length > MAX_QR_IMAGE_BYTES) {
                throw new RenException("网易云二维码图片大小不符合要求");
            }
            boolean validSignature = MediaType.IMAGE_PNG_VALUE.equals(contentType)
                    ? isPng(bytes) : isJpeg(bytes);
            if (!validSignature) throw new RenException("网易云二维码图片内容与格式不匹配");
            validateDecodableImage(bytes);
            return new QrImage(contentType, bytes);
        } catch (IllegalArgumentException exception) {
            throw new RenException("网易云服务返回的二维码图片无效", exception);
        }
    }

    private boolean isPng(byte[] bytes) {
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) return false;
        }
        return true;
    }

    private boolean isJpeg(byte[] bytes) {
        return bytes.length >= 3 && bytes[0] == (byte) 0xff
                && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff;
    }

    private void validateDecodableImage(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            BufferedImage image = ImageIO.read(input);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new RenException("网易云服务返回的二维码图片无法解码");
            }
        } catch (IOException exception) {
            throw new RenException("网易云服务返回的二维码图片无法解码", exception);
        }
    }

    private StatusResponse sessionStatus(DeviceNeteaseSessionEntity session) {
        return new StatusResponse(NeteaseLoginStatus.valueOf(session.getStatus()), session.getSessionId(),
                session.getExpiresAt(), null, null, null, session.getFailureReason());
    }

    private StatusResponse authStatus(DeviceNeteaseAuthEntity auth) {
        return new StatusResponse(NeteaseLoginStatus.LOGGED_IN, null, null, auth.getProviderUserId(),
                auth.getNickname(), auth.getAvatarUrl(), null);
    }

    private void revokeAuth(DeviceNeteaseAuthEntity auth, String reason, String status) {
        auth.setCredentialCiphertext(null);
        auth.setStatus(status);
        auth.setRevokeReason(reason);
        auth.setRevokedAt(new Date());
        auth.setUpdatedAt(new Date());
        auth.setVersion(auth.getVersion() == null ? 1 : auth.getVersion() + 1);
        authDao.updateById(auth);
    }
}
