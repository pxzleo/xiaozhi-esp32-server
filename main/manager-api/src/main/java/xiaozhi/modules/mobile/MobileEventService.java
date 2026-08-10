package xiaozhi.modules.mobile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.mobile.MobileEventDTOs.BatchRequest;
import xiaozhi.modules.mobile.MobileEventDTOs.BatchResponse;
import xiaozhi.modules.mobile.MobileEventDTOs.ConfigResponse;
import xiaozhi.modules.mobile.MobileEventDTOs.EventResult;
import xiaozhi.modules.mobile.MobileEventDTOs.EventStatus;
import xiaozhi.modules.mobile.MobileEventDTOs.NotificationConfig;
import xiaozhi.modules.mobile.MobileEventDTOs.StatusResponse;

@Service
public class MobileEventService {
    private static final int VERSION = 1;
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?iu)(验证码|校验码|动态码|otp)\\s*(?:为|是|[:：=])?\\s*[^,，。；;!?！？、\\r\\n]{1,512}(?=$|[,，。；;!?！？、\\r\\n])"
            + "|(?:\\d[ -]?){4,8}[^\\p{L}\\p{N}]{0,8}(验证码|校验码|动态码|otp)"
            + "|(access[ _-]?token|访问令牌|令牌|token|bearer|password|密码|口令)\\s*(?:为|是|[:：=])?\\s*[^,，。；;!?！？、\\r\\n]{1,512}(?=$|[,，。；;!?！？、\\r\\n])"
            + "|https?://\\S{41,}|(?<!\\d)(?:\\d[ -]?){9,}(?!\\d)");
    private final MobileInstanceDao instanceDao;
    private final MobileEventDao eventDao;
    private final ObjectMapper mapper = new ObjectMapper();

    public record MobileAuth(String instanceId, String installationId, int credentialVersion,
            int protocolVersion, String token) {}

    public MobileEventService(MobileInstanceDao instanceDao, MobileEventDao eventDao) {
        this.instanceDao = instanceDao;
        this.eventDao = eventDao;
    }

    public ConfigResponse config(MobileAuth auth) {
        authenticate(auth);
        return new ConfigResponse(VERSION, new NotificationConfig(true, 200, 50,
                List.of("message", "call", "parcel", "appointment", "security", "other")));
    }

    public String configEtag(ConfigResponse config) {
        return "\"m2-" + MobileAssistantService.hashCredential(config.toString()).substring(0, 16) + "\"";
    }

    @Transactional(rollbackFor = Exception.class)
    public BatchResponse accept(MobileAuth auth, BatchRequest request) {
        MobileInstanceEntity instance = authenticate(auth);
        if (request.version() != VERSION) {
            throw new MobileApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_VERSION", "不支持的手机事件协议版本");
        }
        List<EventResult> results = new ArrayList<>();
        for (var event : request.events()) {
            EventResult result;
            if (!instance.getMobileInstanceId().equals(event.mobileInstanceId()) || event.version() != VERSION) {
                result = new EventResult(event.eventId(), "rejected", "INSTANCE_OR_VERSION_MISMATCH");
            } else if (!event.expiresAt().isAfter(Instant.now())) {
                result = new EventResult(event.eventId(), "expired", "EVENT_EXPIRED");
            } else if (event.occurredAt().isAfter(Instant.now().plusSeconds(300))
                    || event.expiresAt().isAfter(Instant.now().plusSeconds(604800))) {
                result = new EventResult(event.eventId(), "rejected", "INVALID_EVENT_TIME");
            } else if (containsSensitive(event.summary()) || containsSensitive(event.source().channel())
                    || valuesContainSensitive(event.entities())
                    || valuesContainSensitive(event.evidence())) {
                result = new EventResult(event.eventId(), "rejected", "SENSITIVE_CONTENT");
            } else {
                MobileEventEntity entity = toEntity(instance.getMobileInstanceId(), event);
                if (eventDao.insertIgnore(entity) == 1) {
                    result = new EventResult(event.eventId(), "acknowledged", null);
                } else {
                    MobileEventEntity sameId = eventDao.selectByEventId(instance.getMobileInstanceId(), event.eventId());
                    eventDao.updateLatestState(entity);
                    result = new EventResult(event.eventId(), "deduped",
                            sameId == null ? "DUPLICATE_STATE_FLOW" : null);
                }
            }
            insertAudit(instance.getMobileInstanceId(), result);
            results.add(result);
        }
        return new BatchResponse(VERSION, results, 0);
    }

    public StatusResponse status(MobileAuth auth, int limit) {
        MobileInstanceEntity instance = authenticate(auth);
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<EventStatus> statuses = eventDao.selectRecentAudit(instance.getMobileInstanceId(), safeLimit).stream()
                .map(item -> new EventStatus(item.getEventId(), item.getStatus(), item.getReasonCode(),
                        item.getCreatedAt().toInstant())).toList();
        return new StatusResponse(VERSION, statuses);
    }

    MobileInstanceEntity authenticate(MobileAuth auth, String requiredCapability) {
        if (auth == null || auth.protocolVersion() != VERSION || StringUtils.isBlank(auth.instanceId())
                || StringUtils.isBlank(auth.installationId())
                || StringUtils.isBlank(auth.token())) {
            throw unauthorized();
        }
        MobileInstanceEntity instance = instanceDao.selectById(auth.instanceId());
        boolean valid = instance != null && instance.getRevokedAt() == null
                && auth.installationId().equals(instance.getInstallationId())
                && Integer.valueOf(auth.credentialVersion()).equals(instance.getCredentialVersion())
                && storedCapabilities(instance).contains(requiredCapability)
                && MessageDigest.isEqual(MobileAssistantService.hashCredential(auth.token()).getBytes(StandardCharsets.US_ASCII),
                        StringUtils.defaultString(instance.getCredentialHash()).getBytes(StandardCharsets.US_ASCII));
        if (!valid) throw unauthorized();
        return instance;
    }

    private MobileInstanceEntity authenticate(MobileAuth auth) {
        return authenticate(auth, "notification_gateway");
    }

    private Set<String> storedCapabilities(MobileInstanceEntity entity) {
        return Set.of(StringUtils.defaultString(entity.getCapabilities()).split(","));
    }

    private MobileApiException unauthorized() {
        return new MobileApiException(HttpStatus.UNAUTHORIZED, "MOBILE_CREDENTIAL_INVALID", "手机凭据无效或已撤销");
    }

    private boolean containsSensitive(String value) { return value != null && SENSITIVE.matcher(value).find(); }
    private boolean valuesContainSensitive(java.util.Map<String, String> values) {
        return values != null && values.values().stream().anyMatch(this::containsSensitive);
    }

    private void insertAudit(String instanceId, EventResult result) {
        MobileEventAuditEntity audit = new MobileEventAuditEntity();
        audit.setMobileInstanceId(instanceId);
        audit.setEventId(result.eventId());
        audit.setStatus(result.status());
        audit.setReasonCode(result.reasonCode());
        audit.setCreatedAt(new Date());
        eventDao.insertAudit(audit);
    }

    private MobileEventEntity toEntity(String instanceId, MobileEventDTOs.CandidateEvent event) {
        MobileEventEntity entity = new MobileEventEntity();
        entity.setMobileInstanceId(instanceId);
        entity.setEventId(event.eventId());
        entity.setDedupeKey(event.dedupeKey());
        entity.setEventType(event.type());
        entity.setSourcePackage(event.source().packageName());
        entity.setSourceChannel(event.source().channel());
        entity.setEventState(event.state());
        entity.setSummary(event.summary());
        entity.setEntitiesJson(json(event.entities()));
        entity.setEvidenceJson(json(event.evidence()));
        entity.setPrivacyLevel(event.privacyLevel());
        entity.setStatus("acknowledged");
        entity.setOccurredAt(Date.from(event.occurredAt()));
        entity.setExpiresAt(Date.from(event.expiresAt()));
        Date now = new Date();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return entity;
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value == null ? java.util.Map.of() : value); }
        catch (JsonProcessingException error) { throw new IllegalArgumentException("事件结构无法序列化", error); }
    }
}
