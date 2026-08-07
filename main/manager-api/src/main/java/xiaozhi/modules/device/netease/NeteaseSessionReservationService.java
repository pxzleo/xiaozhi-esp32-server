package xiaozhi.modules.device.netease;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

@Service
public class NeteaseSessionReservationService {
    private static final long SESSION_SECONDS = 5 * 60;
    private final DeviceDao deviceDao;
    private final DeviceNeteaseAuthDao authDao;
    private final DeviceNeteaseSessionDao sessionDao;

    public record Reservation(DeviceNeteaseSessionEntity session, boolean existing) {}

    public NeteaseSessionReservationService(DeviceDao deviceDao, DeviceNeteaseAuthDao authDao,
            DeviceNeteaseSessionDao sessionDao) {
        this.deviceDao = deviceDao;
        this.authDao = authDao;
        this.sessionDao = sessionDao;
    }

    @Transactional(rollbackFor = Exception.class)
    public Reservation reserve(String identifier) {
        DeviceEntity device = deviceDao.selectByIdentifierForUpdate(identifier);
        if (device == null || device.getUserId() == null) throw new RenException("设备不存在或尚未绑定");
        String deviceId = StringUtils.defaultIfBlank(device.getMacAddress(), device.getId());
        DeviceNeteaseAuthEntity auth = authDao.selectById(deviceId);
        if (auth != null && "LOGGED_IN".equals(auth.getStatus())) throw new RenException("设备已登录网易云音乐");

        DeviceNeteaseSessionEntity existing = sessionDao.selectOne(
                new LambdaQueryWrapper<DeviceNeteaseSessionEntity>()
                        .eq(DeviceNeteaseSessionEntity::getActiveSlot, deviceId).last("LIMIT 1"));
        if (existing != null && existing.getExpiresAt().after(new Date())) return new Reservation(existing, true);
        if (existing != null) {
            sessionDao.finishIfActive(existing.getSessionId(), deviceId,
                    NeteaseLoginStatus.EXPIRED.name(), null, new Date());
        }

        Date now = new Date();
        DeviceNeteaseSessionEntity session = new DeviceNeteaseSessionEntity();
        session.setSessionId(UUID.randomUUID().toString());
        session.setDeviceId(deviceId);
        session.setStatus(NeteaseLoginStatus.WAITING_SCAN.name());
        session.setExpiresAt(Date.from(Instant.now().plusSeconds(SESSION_SECONDS)));
        session.setActiveSlot(deviceId);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        try {
            sessionDao.insert(session);
        } catch (DuplicateKeyException exception) {
            throw new RenException("设备登录会话并发创建失败，请重试", exception);
        }
        return new Reservation(session, false);
    }
}
