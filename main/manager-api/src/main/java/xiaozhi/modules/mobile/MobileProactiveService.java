package xiaozhi.modules.mobile;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import xiaozhi.modules.device.proactive.ProactiveDTOs.EventClaim;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventView;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.device.proactive.ProactiveService;
import xiaozhi.modules.mobile.MobileProactiveDTOs.ClaimRequest;
import xiaozhi.modules.mobile.MobileProactiveDTOs.ClaimResponse;
import xiaozhi.modules.mobile.MobileProactiveDTOs.CompleteRequest;
import xiaozhi.modules.mobile.MobileProactiveDTOs.CompleteResponse;
import xiaozhi.modules.mobile.MobileProactiveDTOs.Followup;
import xiaozhi.modules.mobile.MobileProactiveDTOs.ExternalContext;
import xiaozhi.modules.mobile.MobileProactiveDTOs.PendingResponse;

@Service
public class MobileProactiveService {
    private final MobileEventService mobileAuth;
    private final ProactiveMonitorService monitorService;
    private final ProactiveService proactiveService;
    private final MobileProactiveAuditDao auditDao;

    public MobileProactiveService(MobileEventService mobileAuth,
            ProactiveMonitorService monitorService, ProactiveService proactiveService,
            MobileProactiveAuditDao auditDao) {
        this.mobileAuth = mobileAuth;
        this.monitorService = monitorService;
        this.proactiveService = proactiveService;
        this.auditDao = auditDao;
    }

    public PendingResponse pending(MobileEventService.MobileAuth auth) {
        MobileInstanceEntity instance = authenticate(auth);
        try {
            return PendingResponse.from(monitorService.pending(instance.getDeviceId()));
        } catch (RenException error) {
            throw new MobileApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROACTIVE_PENDING_UNAVAILABLE", "主动提醒探测暂时不可用");
        }
    }

    @Transactional
    public ClaimResponse claim(MobileEventService.MobileAuth auth, String eventId, ClaimRequest request) {
        requireVersion(request.version);
        MobileInstanceEntity instance = authenticate(auth);
        EventView event;
        try {
            event = proactiveService.monitorEvent(instance.getMobileInstanceId(), eventId);
        } catch (RenException error) {
            throw new MobileApiException(HttpStatus.CONFLICT, "PROACTIVE_EVENT_UNAVAILABLE",
                    "主动事件已失效或当前策略不允许领取");
        }
        Authority authority = authority(event);
        EventClaim claim = new EventClaim();
        claim.setMacAddress(instance.getMobileInstanceId());
        claim.setClaimToken(request.claimToken);
        boolean claimed;
        try {
            claimed = proactiveService.claimEvent(eventId, claim);
        } catch (RenException error) {
            throw new MobileApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PROACTIVE_CLAIM_UNAVAILABLE", "主动事件领取暂时不可用");
        }
        if (!claimed) {
            throw new MobileApiException(HttpStatus.CONFLICT, "PROACTIVE_CLAIM_CONFLICT",
                    "主动事件已由其他前端领取或已失效");
        }
        return new ClaimResponse(1, event.eventId(), request.claimToken, event.topic(), event.priority(),
                authority.title(), authority.summary(), authority.tts(), authority.sensitivity(),
                authority.followup(), authority.externalContext(),
                event.expiresAt() == null ? null : event.expiresAt().getTime());
    }

    @Transactional
    public CompleteResponse complete(MobileEventService.MobileAuth auth, String eventId,
            CompleteRequest request) {
        requireVersion(request.version);
        MobileInstanceEntity instance = authenticate(auth);
        EventStatusUpdate update = new EventStatusUpdate();
        update.setMacAddress(instance.getMobileInstanceId());
        update.setClaimToken(request.claimToken);
        update.setDeliveryStatus("delivered".equals(request.status)
                ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED);
        update.setOutcome("delivered".equals(request.status) ? Outcome.COMPLETED
                : "interrupted".equals(request.status) ? Outcome.INTERRUPTED : Outcome.FAILED);
        try {
            proactiveService.updateEventStatus(eventId, update);
        } catch (RenException error) {
            throw new MobileApiException(HttpStatus.CONFLICT, "PROACTIVE_TERMINAL_CONFLICT",
                    "主动事件终态已变化或领取凭据无效");
        }
        if (auditDao.recordTerminal(instance.getDeviceId(), eventId, request.claimToken,
                request.status, request.reason, new java.util.Date()) != 1) {
            throw new MobileApiException(HttpStatus.CONFLICT, "PROACTIVE_TERMINAL_AUDIT_FAILED",
                    "主动事件真实终态审计失败");
        }
        return new CompleteResponse(1, eventId, request.status, request.reason);
    }

    private MobileInstanceEntity authenticate(MobileEventService.MobileAuth auth) {
        return mobileAuth.authenticate(auth, "voice_session");
    }

    private void requireVersion(int version) {
        if (version != 1) {
            throw new MobileApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_VERSION",
                    "不支持的手机主动提醒协议版本");
        }
    }

    private Authority authority(EventView event) {
        Map<String, Object> payload = event.payload();
        String title = requireText(payload, "title", 100);
        String summary = event.eventType() == xiaozhi.modules.device.proactive.ProactiveEnums.EventType.MOBILE_ALERT
                ? requireText(payload, "summary", 120) : requireText(payload, "message", 300);
        boolean news = event.topic() == Topic.NEWS;
        String tts = news && !summary.endsWith("要了解详情吗？")
                ? summary + " 要了解详情吗？" : summary;
        String source = news ? requireText(payload, "source", 100) : optionalText(payload, "source");
        String referenceUrl = news
                ? requireText(payload, "reference_url", 2048) : optionalText(payload, "reference_url");
        Followup followup = new Followup(news, news ? "news_detail" : null,
                optionalText(payload, "reference_id"), referenceUrl, source);
        ExternalContext externalContext = news
                ? new ExternalContext(title, source,
                        optionalText(payload, "action") == null ? summary : optionalText(payload, "action"),
                        referenceUrl, true)
                : null;
        return new Authority(title, summary, tts, news ? "medium" : "low", followup,
                externalContext);
    }

    private String requireText(Map<String, Object> payload, String key, int max) {
        String value = optionalText(payload, key);
        if (StringUtils.isBlank(value) || value.length() > max) {
            throw new MobileApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "PROACTIVE_AUTHORITY_INVALID", "主动事件权威内容无效");
        }
        return value;
    }

    private String optionalText(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record Authority(String title, String summary, String tts,
            String sensitivity, Followup followup, ExternalContext externalContext) {}
}
