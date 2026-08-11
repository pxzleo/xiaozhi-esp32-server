package xiaozhi.modules.mobile;

import java.time.Instant;
import java.util.Date;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;
import xiaozhi.modules.mobile.MobileEventAuditDTOs.AuditView;

@Service
public class MobileEventAuditService {
    private final MobileInstanceDao instanceDao;
    private final MobileEventDao eventDao;

    public MobileEventAuditService(MobileInstanceDao instanceDao, MobileEventDao eventDao) {
        this.instanceDao = instanceDao;
        this.eventDao = eventDao;
    }

    public PageData<AuditView> audit(Long userId, String instanceId, String type,
            String processingStatus, String deliveryStatus, Instant from, Instant to,
            int page, int limit) {
        MobileInstanceEntity instance = instanceDao.selectById(instanceId);
        if (instance == null || userId == null || !userId.equals(instance.getUserId())) {
            throw new RenException("手机实例不存在");
        }
        if (from != null && to != null && from.isAfter(to)) throw new RenException("事件时间范围无效");
        int safePage = range(page, 1, 1_000, "page");
        int safeLimit = range(limit, 1, 100, "limit");
        Date fromDate = from == null ? null : Date.from(from);
        Date toDate = to == null ? null : Date.from(to);
        String databaseDelivery = StringUtils.isBlank(deliveryStatus) ? null : deliveryStatus.toUpperCase();
        var rows = eventDao.pageAuditForUser(userId, instanceId, type, processingStatus,
                databaseDelivery, fromDate, toDate, safeLimit, ((long) safePage - 1L) * safeLimit);
        long total = eventDao.countAuditForUser(userId, instanceId, type, processingStatus,
                databaseDelivery, fromDate, toDate);
        return new PageData<>(rows.stream().map(this::view).toList(), total);
    }

    private AuditView view(MobileEventAuditRow row) {
        return new AuditView(row.getMobileInstanceId(), row.getDeviceId(), row.getEventId(),
                row.getEventType(), row.getSourcePackage(), row.getEventState(), row.getSummary(),
                row.getCategory(), row.getSeverity(), row.getConfidence(), row.getSpokenSummary(),
                row.getReasonCode(), row.getProcessingStatus(), row.getOccurredAt(), row.getCreatedAt(),
                row.getProcessedAt(), row.getProactiveEventId(), row.getDeliveryStatus());
    }

    private int range(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) throw new RenException(name + "超出允许范围");
        return value;
    }
}
