package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventCreateResult;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.device.proactive.ProactiveService;

class MobileEventProcessingServiceTest {
    private MobileEventDao eventDao;
    private MobileInstanceDao instanceDao;
    private ProactiveMonitorService classifier;
    private ProactiveService proactive;
    private MobileEventProcessingTransactionService transactions;
    private MobileEventProcessingService service;

    @BeforeEach
    void setUp() {
        eventDao = mock(MobileEventDao.class);
        instanceDao = mock(MobileInstanceDao.class);
        classifier = mock(ProactiveMonitorService.class);
        proactive = mock(ProactiveService.class);
        transactions = new MobileEventProcessingTransactionService(eventDao, instanceDao, proactive);
        service = new MobileEventProcessingService(eventDao, classifier, transactions, new ObjectMapper());
        when(eventDao.finishIgnored(any(), any(), any(), any())).thenReturn(1);
        when(eventDao.finishPrefiltered(any(), any(), any(), any())).thenReturn(1);
        when(eventDao.finishClassified(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any())).thenReturn(1);
        when(eventDao.finishConverted(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any())).thenReturn(1);
        when(eventDao.finishError(any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void removedAndExpiredEventsAreDeterministicallyIgnoredWithoutModel() {
        MobileEventEntity removed = event("notification.state_changed", "removed", "message", "重要消息");
        assertEquals(MobileEventProcessingService.IGNORED,
                service.processClaimed(removed, "worker", "token"));
        verify(eventDao).finishIgnored(removed.getMobileInstanceId(), removed.getEventId(),
                "token", "notification_removed");
        verify(classifier, never()).classifyMobileEvent(any(), any(), any(), any());

        MobileEventEntity expired = event("notification.state_changed", "posted", "security", "账户风险");
        expired.setExpiresAt(Date.from(Instant.now().minusSeconds(1)));
        assertEquals(MobileEventProcessingService.IGNORED,
                service.processClaimed(expired, "worker", "token2"));
        verify(eventDao).finishIgnored(expired.getMobileInstanceId(), expired.getEventId(),
                "token2", "event_expired");
    }

    @Test
    void locationUsesServerRebuiltSummaryAndNeverCallsModel() {
        MobileEventEntity location = event("location.transition", "entered", null, "客户端文本");
        location.setEntitiesJson("{\"place_id\":\"place_12345678\",\"place_name\":\"公司\",\"transition\":\"enter\"}");
        location.setProcessingLeaseToken("token");
        when(instanceDao.selectById(location.getMobileInstanceId())).thenReturn(instance());
        when(eventDao.selectByEventIdForUpdate(location.getMobileInstanceId(), location.getEventId()))
                .thenReturn(location);
        when(proactive.createMobileAlert(eq(location.getMobileInstanceId()), eq(7L), eq("agent-1"),
                eq(location.getEventId()),
                org.mockito.ArgumentMatchers.startsWith("sha256:"), eq("地点提醒"), eq("已进入公司"),
                eq("android.geofence"), eq("location"), eq(Priority.NORMAL), any(), any()))
                .thenReturn(new EventCreateResult(true, false, "mobile-1", null, new Date()));

        assertEquals(MobileEventProcessingService.CONVERTED,
                service.processClaimed(location, "worker", "token"));
        verify(eventDao).finishConverted(location.getMobileInstanceId(), location.getEventId(),
                "token", "location", "medium", 1.0, "已进入公司", "mobile-1");
        verify(classifier, never()).classifyMobileEvent(any(), any(), any(), any());
    }

    @Test
    void updatedNotificationUsesRevisionDedupeAndLatestControlledPayload() {
        MobileEventEntity event = event("notification.state_changed", "updated", "security", "账户风险状态已更新");
        event.setProcessingLeaseToken("token");
        when(instanceDao.selectById(event.getMobileInstanceId())).thenReturn(instance());
        when(eventDao.selectByEventIdForUpdate(event.getMobileInstanceId(), event.getEventId()))
                .thenReturn(event);
        when(classifier.classifyMobileEvent(event.getSummary(), "security",
                event.getSourcePackage(), event.getEventState()))
                .thenReturn(new ProactiveMonitorService.MobileAlertClassification(
                        true, "security", "high", 0.85, "账户出现安全风险", "security_risk"));
        when(proactive.createMobileAlert(eq(event.getMobileInstanceId()), eq(7L), eq("agent-1"),
                eq(event.getEventId()),
                org.mockito.ArgumentMatchers.argThat(key -> key.matches("sha256:[0-9a-f]{64}")
                        && !key.equals(event.getDedupeKey())),
                eq("安全提醒"), eq("账户出现安全风险"),
                eq(event.getSourcePackage()), eq("security"), eq(Priority.HIGH), any(), any()))
                .thenReturn(new EventCreateResult(true, false, "mobile-1", null, new Date()));

        assertEquals(MobileEventProcessingService.CONVERTED,
                service.processClaimed(event, "worker", "token"));
        verify(proactive).createMobileAlert(eq(event.getMobileInstanceId()), eq(7L), eq("agent-1"),
                eq(event.getEventId()),
                org.mockito.ArgumentMatchers.argThat(key -> key.matches("sha256:[0-9a-f]{64}")
                        && !key.equals(event.getDedupeKey())),
                any(), eq("账户出现安全风险"), any(), any(), eq(Priority.HIGH), any(), any());
        verify(eventDao).finishConverted(event.getMobileInstanceId(), event.getEventId(), "token",
                "security", "high", 0.85, "账户出现安全风险", "mobile-1");
    }

    @Test
    void lowConfidenceAndModelFailureNeverCreateSpeechEvent() {
        MobileEventEntity event = event("notification.state_changed", "posted", "parcel", "包裹已到驿站");
        when(classifier.classifyMobileEvent(any(), any(), any(), any()))
                .thenReturn(new ProactiveMonitorService.MobileAlertClassification(
                        true, "parcel", "high", 0.849, "包裹已到", "parcel_arrived"));
        assertEquals(MobileEventProcessingService.CLASSIFIED,
                service.processClaimed(event, "worker", "token"));
        verify(eventDao).finishClassified(event.getMobileInstanceId(), event.getEventId(), "token",
                "parcel", "high", 0.849, "包裹已到", "classification_below_threshold");
        verify(proactive, never()).createMobileAlert(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());

        when(classifier.classifyMobileEvent(any(), any(), any(), any()))
                .thenThrow(new RenException("外界分类模型未配置"));
        assertEquals(MobileEventProcessingService.ERROR,
                service.processClaimed(event, "worker", "token2"));
        verify(eventDao).finishError(eq(event.getMobileInstanceId()), eq(event.getEventId()),
                eq("token2"), eq("classifier_unavailable"), any());
    }

    @Test
    void updatedLowValueNotificationStopsAtDeterministicPrefilter() {
        MobileEventEntity event = event(
                "notification.state_changed", "updated", "message", "今日内容更新");

        assertEquals(MobileEventProcessingService.PREFILTERED,
                service.processClaimed(event, "worker", "token"));
        verify(eventDao).finishPrefiltered(event.getMobileInstanceId(), event.getEventId(),
                "token", "prefilter_low_value");
        verify(classifier, never()).classifyMobileEvent(any(), any(), any(), any());
    }

    @Test
    void databaseClaimCasLetsOnlyTheWinningWorkerProcessCandidate() {
        MobileEventEntity candidate = event(
                "notification.state_changed", "posted", "security", "账户存在异常登录风险");
        when(eventDao.selectProcessingCandidates(10)).thenReturn(List.of(candidate));
        when(eventDao.claimProcessing(eq(candidate.getMobileInstanceId()), eq(candidate.getEventId()),
                eq("worker-a"), any())).thenReturn(0);

        assertEquals(0, service.processBatch("worker-a", 10));

        verify(eventDao).ignoreExpired();
        verify(classifier, never()).classifyMobileEvent(any(), any(), any(), any());
        verify(proactive, never()).createMobileAlert(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void workerRereadsAuthoritativeStateAfterWinningLease() {
        MobileEventEntity stale = event(
                "notification.state_changed", "posted", "security", "账户存在异常登录风险");
        MobileEventEntity removed = event(
                "notification.state_changed", "removed", "security", "账户存在异常登录风险");
        when(eventDao.selectProcessingCandidates(10)).thenReturn(List.of(stale));
        when(eventDao.claimProcessing(eq(stale.getMobileInstanceId()), eq(stale.getEventId()),
                eq("worker-a"), any())).thenReturn(1);
        when(eventDao.selectByEventId(stale.getMobileInstanceId(), stale.getEventId()))
                .thenReturn(removed);

        assertEquals(1, service.processBatch("worker-a", 10));

        verify(eventDao).finishIgnored(eq(removed.getMobileInstanceId()), eq(removed.getEventId()),
                any(), eq("notification_removed"));
        verify(classifier, never()).classifyMobileEvent(any(), any(), any(), any());
    }

    @Test
    void newerRevisionWinsBeforeProactiveCreationInsideFinalTransaction() {
        MobileEventEntity stale = event(
                "notification.state_changed", "posted", "security", "账户存在异常登录风险");
        MobileEventEntity updated = event(
                "notification.state_changed", "updated", "message", "今日内容更新");
        updated.setOccurredAt(Date.from(stale.getOccurredAt().toInstant().plusSeconds(1)));
        when(eventDao.selectByEventIdForUpdate(stale.getMobileInstanceId(), stale.getEventId()))
                .thenReturn(updated);

        var command = new MobileEventProcessingTransactionService.ConversionCommand(
                stale, "old-token", "security", "high", 0.9, "账户存在安全风险",
                "安全提醒", "sha256:" + "b".repeat(64), stale.getSourcePackage(), Priority.HIGH);

        assertEquals(MobileEventProcessingTransactionService.SUPERSEDED,
                transactions.convert(command).status());
        verify(proactive, never()).createMobileAlert(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(eventDao, never()).finishConverted(any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any());
    }

    private MobileEventEntity event(String type, String state, String category, String summary) {
        MobileEventEntity event = new MobileEventEntity();
        event.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        event.setEventId("evt-1");
        event.setDedupeKey("sha256:" + "a".repeat(64));
        event.setEventType(type);
        event.setEventState(state);
        event.setSourcePackage("com.example.app");
        event.setSummary(summary);
        event.setEntitiesJson(category == null ? "{}" : "{\"category\":\"" + category + "\"}");
        event.setOccurredAt(new Date());
        event.setExpiresAt(Date.from(Instant.now().plusSeconds(600)));
        event.setProcessingAttempt(1);
        return event;
    }

    private MobileInstanceEntity instance() {
        MobileInstanceEntity instance = new MobileInstanceEntity();
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        instance.setDeviceId("device-mobile");
        instance.setUserId(7L);
        instance.setAgentId("agent-1");
        return instance;
    }
}
