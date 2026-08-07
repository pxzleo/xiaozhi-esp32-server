package xiaozhi.modules.device.netease;

import java.util.Date;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NeteaseLoginCompletionService {
    private final DeviceNeteaseSessionDao sessionDao;
    private final DeviceNeteaseAuthDao authDao;
    private final NeteaseCredentialCipher cipher;

    public NeteaseLoginCompletionService(DeviceNeteaseSessionDao sessionDao,
            DeviceNeteaseAuthDao authDao, NeteaseCredentialCipher cipher) {
        this.sessionDao = sessionDao;
        this.authDao = authDao;
        this.cipher = cipher;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean complete(String sessionId, String deviceId, String cookie, String providerUserId,
            String nickname, String avatarUrl) {
        Date now = new Date();
        int claimed = sessionDao.completeIfActive(sessionId, deviceId, now);
        if (claimed != 1) return false;

        DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
        boolean insert = auth == null;
        if (insert) {
            auth = new DeviceNeteaseAuthEntity();
            auth.setDeviceId(deviceId);
            auth.setCreatedAt(now);
            auth.setVersion(1);
        }
        auth.setCredentialCiphertext(cipher.encrypt(cookie));
        auth.setProviderUserId(providerUserId);
        auth.setNickname(nickname);
        auth.setAvatarUrl(avatarUrl);
        auth.setStatus("LOGGED_IN");
        auth.setRevokeReason(null);
        auth.setRevokedAt(null);
        auth.setUpdatedAt(now);
        if (!insert) auth.setVersion(auth.getVersion() == null ? 1 : auth.getVersion() + 1);
        if (insert) authDao.insert(auth); else authDao.updateById(auth);
        return true;
    }
}
