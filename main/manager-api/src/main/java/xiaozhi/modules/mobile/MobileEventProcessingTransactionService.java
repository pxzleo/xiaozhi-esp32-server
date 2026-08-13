package xiaozhi.modules.mobile;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveService;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingService;

@Service
public class MobileEventProcessingTransactionService {
    public static final String SUPERSEDED = "superseded";

    private final MobileEventDao eventDao;
    private final MobileInstanceDao instanceDao;
    private final ProactiveService proactiveService;
    private final MobileAlertDecisionPolicy decisionPolicy;
    private final ProactiveDeliveryRoutingService routingService;

    public MobileEventProcessingTransactionService(MobileEventDao eventDao,
            MobileInstanceDao instanceDao, ProactiveService proactiveService,
            MobileAlertDecisionPolicy decisionPolicy,
            ProactiveDeliveryRoutingService routingService) {
        this.eventDao = eventDao;
        this.instanceDao = instanceDao;
        this.proactiveService = proactiveService;
        this.decisionPolicy = decisionPolicy;
        this.routingService = routingService;
    }

    public MobileInstanceEntity instance(String instanceId) {
        return instanceDao.selectCanonicalByInstance(instanceId);
    }

    public record ConversionCommand(MobileEventEntity event, String token, String category,
            String severity, double confidence, String spokenSummary, String title,
            String dedupeKey, String source, Priority priority, boolean notification,
            boolean modelClassified) {}

    public record ConversionResult(String status, String proactiveEventId) {}

    @Transactional(rollbackFor = Exception.class)
    public MobileEventEntity claimAndRead(MobileEventEntity candidate, String owner, String token) {
        if (eventDao.claimProcessing(candidate.getMobileInstanceId(), candidate.getEventId(),
                owner, token) != 1) {
            return null;
        }
        MobileEventEntity authoritative = eventDao.selectByEventId(
                candidate.getMobileInstanceId(), candidate.getEventId());
        if (authoritative == null) throw new RenException("已领取的手机事件不存在");
        return authoritative;
    }

    @Transactional(rollbackFor = Exception.class)
    public ConversionResult convert(ConversionCommand command) {
        MobileEventEntity event = command.event();
        MobileInstanceEntity unlockedInstance = instanceDao.selectCanonicalByInstance(
                event.getMobileInstanceId());
        if (unlockedInstance == null) return new ConversionResult(SUPERSEDED, null);
        routingService.lockUser(unlockedInstance.getUserId());
        MobileEventEntity authoritative = eventDao.selectByEventIdForUpdate(
                event.getMobileInstanceId(), event.getEventId());
        if (!sameRevision(authoritative, event, command.token())) {
            return new ConversionResult(SUPERSEDED, null);
        }
        MobileInstanceEntity instance = instanceDao.selectCanonicalByInstanceForUpdate(
                event.getMobileInstanceId());
        if (instance == null || instance.getRevokedAt() != null) {
            if (eventDao.finishIgnored(event.getMobileInstanceId(), event.getEventId(),
                    command.token(), "mobile_instance_unavailable") != 1) {
                throw new RenException("手机事件忽略终态写入失败");
            }
            return new ConversionResult(MobileEventProcessingService.IGNORED, null);
        }
        if (command.notification()
                && !decisionPolicy.enabledCategory(instance.getAlertCategories(), command.category())) {
            finishClassified(command, "category_disabled");
            return new ConversionResult(MobileEventProcessingService.CLASSIFIED, null);
        }
        if (command.modelClassified()
                && !decisionPolicy.acceptModel(instance.getAlertSensitivity(),
                        command.severity(), command.confidence())) {
            finishClassified(command, "classification_below_threshold");
            return new ConversionResult(MobileEventProcessingService.CLASSIFIED, null);
        }
        var created = proactiveService.createMobileAlert(event.getMobileInstanceId(),
                instance.getUserId(), instance.getAgentId(), event.getEventId(),
                command.dedupeKey(), command.title(), command.spokenSummary(), command.source(),
                command.category(), command.priority(), event.getOccurredAt(), event.getExpiresAt());
        if (eventDao.finishConverted(event.getMobileInstanceId(), event.getEventId(), command.token(),
                command.category(), command.severity(), command.confidence(), command.spokenSummary(),
                created.authoritativeEventId()) != 1) {
            throw new RenException("手机事件转换终态写入失败");
        }
        return new ConversionResult(MobileEventProcessingService.CONVERTED,
                created.authoritativeEventId());
    }

    private void finishClassified(ConversionCommand command, String reason) {
        MobileEventEntity event = command.event();
        if (eventDao.finishClassified(event.getMobileInstanceId(), event.getEventId(),
                command.token(), command.category(), command.severity(), command.confidence(),
                command.spokenSummary(), reason) != 1) {
            throw new RenException("手机事件分类终态写入失败");
        }
    }

    private boolean sameRevision(MobileEventEntity authoritative, MobileEventEntity candidate,
            String token) {
        return authoritative != null
                && Objects.equals(token, authoritative.getProcessingLeaseToken())
                && Objects.equals(candidate.getOccurredAt(), authoritative.getOccurredAt())
                && Objects.equals(candidate.getDedupeKey(), authoritative.getDedupeKey())
                && Objects.equals(candidate.getEventState(), authoritative.getEventState());
    }
}
