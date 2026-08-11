package xiaozhi.modules.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.device.proactive.ProactiveMonitorService.MobileAlertClassification;
import xiaozhi.modules.device.proactive.ProactiveService;

@Service
public class MobileEventProcessingService {
    public static final String IGNORED = "ignored";
    public static final String PREFILTERED = "prefiltered";
    public static final String CLASSIFIED = "classified";
    public static final String CONVERTED = "converted";
    public static final String ERROR = "error";
    private static final Set<String> CATEGORIES = Set.of(
            "security", "call", "parcel", "appointment", "message", "other");
    private static final Pattern SECURITY = Pattern.compile("风险|安全|异常|登录|支付|账户|诈骗|危险");
    private static final Pattern CALL = Pattern.compile("未接来电|来电|电话|回拨");
    private static final Pattern PARCEL = Pattern.compile("包裹|快递|取件|驿站|快递柜|派送|到达");
    private static final Pattern APPOINTMENT = Pattern.compile("预约|日程|会议|就诊|上门|航班|火车|出发");
    private static final Pattern IMPORTANT_MESSAGE = Pattern.compile("重要|紧急|尽快|回复|联系|截止|到期");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};

    private final MobileEventDao eventDao;
    private final MobileInstanceDao instanceDao;
    private final ProactiveMonitorService classifier;
    private final ProactiveService proactiveService;
    private final ObjectMapper objectMapper;

    public MobileEventProcessingService(MobileEventDao eventDao, MobileInstanceDao instanceDao,
            ProactiveMonitorService classifier, ProactiveService proactiveService,
            ObjectMapper objectMapper) {
        this.eventDao = eventDao;
        this.instanceDao = instanceDao;
        this.classifier = classifier;
        this.proactiveService = proactiveService;
        this.objectMapper = objectMapper;
    }

    @Transactional(rollbackFor = Exception.class)
    public int processBatch(String owner, int limit) {
        if (StringUtils.isBlank(owner) || owner.length() > 64 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("手机事件处理批次参数无效");
        }
        eventDao.ignoreExpired();
        int processed = 0;
        for (MobileEventEntity event : eventDao.selectProcessingCandidates(limit)) {
            String token = UUID.randomUUID().toString();
            if (eventDao.claimProcessing(event.getMobileInstanceId(), event.getEventId(), owner, token) == 1) {
                MobileEventEntity authoritative = eventDao.selectByEventId(
                        event.getMobileInstanceId(), event.getEventId());
                if (authoritative == null) throw new RenException("已领取的手机事件不存在");
                processClaimed(authoritative, owner, token);
                processed++;
            }
        }
        return processed;
    }

    String processClaimed(MobileEventEntity event, String owner, String token) {
        if (event.getExpiresAt() == null || !event.getExpiresAt().after(new Date())) {
            finishIgnored(event, token, "event_expired");
            return IGNORED;
        }
        if ("notification.state_changed".equals(event.getEventType())) {
            return processNotification(event, token);
        }
        if ("location.transition".equals(event.getEventType())) {
            return processLocation(event, token);
        }
        finishIgnored(event, token, "event_type_unsupported");
        return IGNORED;
    }

    private String processNotification(MobileEventEntity event, String token) {
        if ("removed".equals(event.getEventState())) {
            finishIgnored(event, token, "notification_removed");
            return IGNORED;
        }
        Map<String, String> entities;
        try {
            entities = readEntities(event);
        } catch (RenException error) {
            finishIgnored(event, token, "stored_event_shape_invalid");
            return IGNORED;
        }
        String category = entities.get("category");
        if (!CATEGORIES.contains(category) || !prefilter(category, event.getSummary())) {
            if (eventDao.finishPrefiltered(event.getMobileInstanceId(), event.getEventId(), token,
                    "prefilter_low_value") != 1) {
                throw new RenException("手机事件预筛终态写入失败");
            }
            return PREFILTERED;
        }
        final MobileAlertClassification result;
        try {
            result = classifier.classifyMobileEvent(event.getSummary(), category,
                    event.getSourcePackage(), event.getEventState());
        } catch (RenException error) {
            finishError(event, token, "classifier_unavailable");
            return ERROR;
        }
        boolean notify = result.shouldNotify()
                && Set.of("high", "critical").contains(result.severity())
                && result.confidence() >= 0.85;
        if (!notify) {
            String reason = result.shouldNotify() ? "classification_below_threshold"
                    : result.reasonCode();
            finishClassified(event, token, result, reason);
            return CLASSIFIED;
        }
        return convert(event, token, result.category(), result.severity(), result.confidence(),
                result.spokenSummary(), title(result.category()), notificationRevisionDedupe(event),
                event.getSourcePackage(), "critical".equals(result.severity())
                        ? Priority.CRITICAL : Priority.HIGH);
    }

    private String processLocation(MobileEventEntity event, String token) {
        final Map<String, String> entities;
        try {
            entities = readEntities(event);
        } catch (RenException error) {
            finishIgnored(event, token, "stored_event_shape_invalid");
            return IGNORED;
        }
        String transition = entities.get("transition");
        String prefix = Map.of("enter", "已进入", "exit", "已离开", "dwell", "已驻留")
                .get(transition);
        String placeId = entities.get("place_id");
        String placeName = entities.get("place_name");
        if (prefix == null || StringUtils.isAnyBlank(placeId, placeName)) {
            finishIgnored(event, token, "stored_event_shape_invalid");
            return IGNORED;
        }
        MobileInstanceEntity instance = instanceDao.selectById(event.getMobileInstanceId());
        if (instance == null || instance.getRevokedAt() != null) {
            finishIgnored(event, token, "mobile_instance_unavailable");
            return IGNORED;
        }
        String summary = prefix + placeName;
        String dedupeKey = "sha256:" + sha256(placeId + ":" + transition);
        return convert(event, token, "location", "medium", 1.0, summary,
                "地点提醒", dedupeKey, "android.geofence", Priority.NORMAL);
    }

    private String convert(MobileEventEntity event, String token, String category,
            String severity, double confidence, String spokenSummary, String title,
            String dedupeKey, String source, Priority priority) {
        MobileInstanceEntity instance = instanceDao.selectById(event.getMobileInstanceId());
        if (instance == null || instance.getRevokedAt() != null) {
            finishIgnored(event, token, "mobile_instance_unavailable");
            return IGNORED;
        }
        try {
            var created = proactiveService.createMobileAlert(event.getMobileInstanceId(),
                    instance.getUserId(), instance.getAgentId(), event.getEventId(),
                    dedupeKey, title, spokenSummary, source, category,
                    priority, event.getOccurredAt(), event.getExpiresAt());
            if (eventDao.finishConverted(event.getMobileInstanceId(), event.getEventId(), token,
                    category, severity, confidence, spokenSummary,
                    created.authoritativeEventId()) != 1) {
                throw new RenException("手机事件转换终态写入失败");
            }
            return CONVERTED;
        } catch (RenException error) {
            finishError(event, token, "proactive_event_unavailable");
            return ERROR;
        }
    }

    private boolean prefilter(String category, String summary) {
        if (StringUtils.isBlank(summary)) return false;
        return switch (category) {
            case "security" -> SECURITY.matcher(summary).find();
            case "call" -> CALL.matcher(summary).find();
            case "parcel" -> PARCEL.matcher(summary).find();
            case "appointment" -> APPOINTMENT.matcher(summary).find();
            case "message", "other" -> IMPORTANT_MESSAGE.matcher(summary).find();
            default -> false;
        };
    }

    private String title(String category) {
        return switch (category) {
            case "security" -> "安全提醒";
            case "call" -> "来电提醒";
            case "parcel" -> "包裹提醒";
            case "appointment" -> "预约提醒";
            case "message" -> "重要消息";
            default -> "手机提醒";
        };
    }

    private Map<String, String> readEntities(MobileEventEntity event) {
        try {
            return objectMapper.readValue(event.getEntitiesJson(), STRING_MAP);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new RenException("手机事件实体格式无效", error);
        }
    }

    private void finishIgnored(MobileEventEntity event, String token, String reason) {
        if (eventDao.finishIgnored(event.getMobileInstanceId(), event.getEventId(), token, reason) != 1) {
            throw new RenException("手机事件忽略终态写入失败");
        }
    }

    private void finishClassified(MobileEventEntity event, String token,
            MobileAlertClassification result, String reason) {
        if (eventDao.finishClassified(event.getMobileInstanceId(), event.getEventId(), token,
                result.category(), result.severity(), result.confidence(),
                result.spokenSummary(), reason) != 1) {
            throw new RenException("手机事件分类终态写入失败");
        }
    }

    private void finishError(MobileEventEntity event, String token, String reason) {
        int attempt = Math.max(1, event.getProcessingAttempt() == null
                ? 1 : event.getProcessingAttempt() + 1);
        long delay = Math.min(3600L, 30L << Math.min(attempt - 1, 7));
        Date nextAttempt = Date.from(Instant.now().plusSeconds(delay));
        if (eventDao.finishError(event.getMobileInstanceId(), event.getEventId(), token,
                reason, nextAttempt) != 1) {
            throw new RenException("手机事件错误终态写入失败");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("JVM不支持SHA-256", error);
        }
    }

    private String notificationRevisionDedupe(MobileEventEntity event) {
        if (event.getOccurredAt() == null || StringUtils.isBlank(event.getDedupeKey())) {
            throw new RenException("手机通知修订去重参数无效");
        }
        return "sha256:" + sha256(event.getDedupeKey() + ":" + event.getOccurredAt().getTime());
    }
}
