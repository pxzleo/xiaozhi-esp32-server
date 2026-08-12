package xiaozhi.modules.mobile;

import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.mobile.MobileEventAuditDTOs.AuditView;
import xiaozhi.modules.mobile.MobileAlertSettingsDTOs.SettingsUpdate;
import xiaozhi.modules.mobile.MobileAlertSettingsDTOs.SettingsView;

@Service
public class MobileEventAuditService {
    private static final java.util.Set<String> ALERT_CATEGORIES = java.util.Set.of(
            "security", "call", "parcel", "appointment", "message", "other");
    private final MobileInstanceDao instanceDao;
    private final MobileEventDao eventDao;

    public MobileEventAuditService(MobileInstanceDao instanceDao, MobileEventDao eventDao) {
        this.instanceDao = instanceDao;
        this.eventDao = eventDao;
    }

    public PageData<AuditView> audit(Long userId, String instanceId, String type,
            String processingStatus, String deliveryStatus, Instant from, Instant to,
            int page, int limit) {
        MobileInstanceEntity instance = instanceDao.selectCanonicalByInstance(instanceId);
        if (instance == null || userId == null || !userId.equals(instance.getUserId())) {
            throw new RenException("手机实例不存在");
        }
        if (from != null && to != null && from.isAfter(to)) throw new RenException("事件时间范围无效");
        int safePage = range(page, 1, 1_000, "page");
        int safeLimit = range(limit, 1, 100, "limit");
        Long fromEpochMillis = from == null ? null : from.toEpochMilli();
        Long toEpochMillis = to == null ? null : to.toEpochMilli();
        String databaseDelivery = StringUtils.isBlank(deliveryStatus) ? null : deliveryStatus.toUpperCase();
        String canonicalInstanceId = instance.getMobileInstanceId();
        var rows = eventDao.pageAuditForUser(userId, canonicalInstanceId, type, processingStatus,
                databaseDelivery, fromEpochMillis, toEpochMillis, safeLimit, ((long) safePage - 1L) * safeLimit);
        long total = eventDao.countAuditForUser(userId, canonicalInstanceId, type, processingStatus,
                databaseDelivery, fromEpochMillis, toEpochMillis);
        return new PageData<>(rows.stream().map(this::view).toList(), total);
    }

    public SettingsView settings(Long userId, String instanceId) {
        MobileInstanceEntity instance = owned(userId, instanceId);
        return settingsView(instance);
    }

    public SettingsView updateSettings(Long userId, String instanceId, SettingsUpdate request) {
        MobileInstanceEntity instance = owned(userId, instanceId);
        java.util.LinkedHashSet<String> categories = new java.util.LinkedHashSet<>(request.categories());
        if (categories.size() != request.categories().size() || !ALERT_CATEGORIES.containsAll(categories)) {
            throw new RenException("手机提醒类别无效");
        }
        String joined = String.join(",", categories);
        if (instanceDao.updateAlertSettings(userId, instance.getMobileInstanceId(),
                request.sensitivity(), joined) != 1) {
            throw new RenException("手机提醒设置保存失败");
        }
        instance.setAlertSensitivity(request.sensitivity());
        instance.setAlertCategories(joined);
        return settingsView(instance);
    }

    private MobileInstanceEntity owned(Long userId, String instanceId) {
        MobileInstanceEntity instance = instanceDao.selectCanonicalByInstance(instanceId);
        if (instance == null || userId == null || !userId.equals(instance.getUserId())) {
            throw new RenException("手机实例不存在");
        }
        return instance;
    }

    private SettingsView settingsView(MobileInstanceEntity instance) {
        String sensitivity = StringUtils.defaultIfBlank(instance.getAlertSensitivity(), "balanced");
        String raw = StringUtils.defaultIfBlank(instance.getAlertCategories(),
                "security,call,parcel,appointment,message,other");
        return new SettingsView(instance.getMobileInstanceId(), sensitivity,
                java.util.Arrays.stream(raw.split(",")).filter(ALERT_CATEGORIES::contains).toList());
    }

    private AuditView view(MobileEventAuditRow row) {
        return new AuditView(row.getMobileInstanceId(), row.getDeviceId(), row.getEventId(),
                row.getEventType(), row.getSourcePackage(), row.getEventState(), row.getSummary(),
                row.getCategory(), row.getSeverity(), row.getConfidence(), row.getSpokenSummary(),
                row.getReasonCode(), row.getProcessingStatus(), row.getOccurredAtEpochMillis(),
                row.getCreatedAtEpochMillis(), row.getProcessedAtEpochMillis(),
                row.getProactiveEventId(), row.getDeliveryStatus());
    }

    private int range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new RenException(name + "超出允许范围");
        return value;
    }
}
