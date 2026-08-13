package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Date;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import xiaozhi.modules.device.proactive.ProactiveDTOs.EventView;
import xiaozhi.modules.device.proactive.ProactivePreferenceConflictException;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PendingEnvelope;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;
import xiaozhi.modules.device.proactive.ProactiveService;

class MobileProactiveServiceTest {
    private MobileEventService mobileAuth;
    private ProactiveMonitorService monitors;
    private ProactiveService proactive;
    private MobileProactiveService service;
    private MobileProactiveAuditDao auditDao;
    private MobileInstanceEntity instance;
    private MobileEventService.MobileAuth auth;

    @BeforeEach
    void setUp() {
        mobileAuth = mock(MobileEventService.class);
        monitors = mock(ProactiveMonitorService.class);
        proactive = mock(ProactiveService.class);
        auditDao = mock(MobileProactiveAuditDao.class);
        service = new MobileProactiveService(mobileAuth, monitors, proactive, auditDao);
        instance = new MobileInstanceEntity();
        instance.setDeviceId("dev-mobile");
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        auth = new MobileEventService.MobileAuth(instance.getMobileInstanceId(),
                "00000000-0000-0000-0000-000000000001", 1, 1, "token");
        when(mobileAuth.authenticate(auth, "voice_session")).thenReturn(instance);
        when(auditDao.recordTerminal(any(), any(), any(), any(), any(), any())).thenReturn(1);
    }

    @Test
    void quietHoursUseTheSameServerPreferenceAsSpeakerAndWeb() {
        var preference = new xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceView(
                "dev-mobile", instance.getMobileInstanceId(),
                xiaozhi.modules.device.proactive.ProactiveEnums.Mode.AGGRESSIVE, 0,
                LocalTime.of(22, 30), LocalTime.of(7, 15), java.util.Set.of(), java.util.Set.of(),
                null, null, null, 3, new Date());
        when(proactive.getPreference(7L, "dev-mobile")).thenReturn(preference);
        instance.setUserId(7L);

        var read = service.quietHours(auth);
        assertEquals("22:30", read.quietStart());
        assertEquals("07:15", read.quietEnd());

        var request = new MobileProactiveDTOs.QuietHoursRequest();
        request.version = 1;
        request.quietStart = "23:00";
        request.quietEnd = "06:30";
        when(proactive.updateQuietHours(7L, "dev-mobile", LocalTime.of(23, 0), LocalTime.of(6, 30)))
                .thenReturn(preference);
        service.updateQuietHours(auth, request);
        verify(proactive).updateQuietHours(7L, "dev-mobile", LocalTime.of(23, 0), LocalTime.of(6, 30));
    }

    @Test
    void quietHoursConcurrentWebSaveReturnsControlledConflict() {
        instance.setUserId(7L);
        var request = new MobileProactiveDTOs.QuietHoursRequest();
        request.version = 1;
        request.setQuietStart("23:00");
        request.setQuietEnd("06:30");
        when(proactive.updateQuietHours(7L, "dev-mobile", LocalTime.of(23, 0), LocalTime.of(6, 30)))
                .thenThrow(new ProactivePreferenceConflictException());

        var error = assertThrows(MobileApiException.class,
                () -> service.updateQuietHours(auth, request));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("PROACTIVE_PREFERENCE_CONFLICT", error.getErrorCode());
    }

    @Test
    void pendingNeverExposesAuthoritativeText() {
        Date expires = Date.from(Instant.now().plusSeconds(300));
        when(monitors.pending("dev-mobile")).thenReturn(
                new PendingEnvelope(true, "ext-1", Topic.NEWS, Priority.HIGH,
                        new Date(), expires, 0));
        var result = service.pending(auth);
        assertTrue(result.pending());
        assertEquals("ext-1", result.eventId());
        assertEquals(expires.getTime(), result.expiresAt());
        var fields = java.util.Arrays.stream(MobileProactiveDTOs.PendingResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertFalse(fields.contains("title"));
        assertFalse(fields.contains("summary"));
        assertFalse(fields.contains("tts"));
    }

    @Test
    void claimReturnsDatabaseAuthorityAndNewsFollowup() {
        when(proactive.monitorEvent(instance.getMobileInstanceId(), "ext-1"))
                .thenReturn(event(Topic.NEWS, Map.of("title", "重大新闻", "message", "已确认事实。",
                        "source", "财联社", "reference_url", "https://example.test/news")));
        when(proactive.claimEvent(any(), any())).thenReturn(true);
        var request = new MobileProactiveDTOs.ClaimRequest();
        request.version = 1;
        request.claimToken = "claim-1";
        var response = service.claim(auth, "ext-1", request);
        assertEquals("重大新闻", response.title());
        assertEquals("已确认事实。 要了解详情吗？", response.tts());
        assertTrue(response.followup().enabled());
        assertEquals("news_detail", response.followup().type());
        assertEquals("重大新闻", response.externalContext().title());
        assertEquals("财联社", response.externalContext().source());
        assertEquals("已确认事实。", response.externalContext().facts());
        assertEquals("https://example.test/news", response.externalContext().referenceUrl());
    }

    @Test
    void briefingReminderWithNewsTopicDoesNotRequireNewsAlertFieldsOrFollowup() {
        EventView briefing = new EventView("dev-mobile", instance.getMobileInstanceId(),
                "schedule-1", Topic.NEWS, Priority.HIGH, "authoritative_schedule_triggered",
                EventType.REMINDER, Map.of("title", "每日简报", "message", "每日简报",
                        "action", "weather,news", "source", "广州"),
                new Date(), Date.from(Instant.now().plusSeconds(300)), "dedupe", true,
                DeliveryStatus.PENDING, Outcome.NONE, null);
        when(proactive.monitorEvent(instance.getMobileInstanceId(), "schedule-1"))
                .thenReturn(briefing);
        when(proactive.claimEvent(any(), any())).thenReturn(true);
        var request = new MobileProactiveDTOs.ClaimRequest();
        request.version = 1; request.claimToken = "briefing-claim";

        var response = service.claim(auth, "schedule-1", request);

        assertEquals("每日简报", response.tts());
        assertFalse(response.followup().enabled());
        assertEquals(null, response.externalContext());
    }

    @Test
    void competingClaimReturnsConflict() {
        when(proactive.monitorEvent(instance.getMobileInstanceId(), "ext-1"))
                .thenReturn(event(Topic.WEATHER, Map.of("title", "暴雨预警", "message", "请减少外出")));
        when(proactive.claimEvent(any(), any())).thenReturn(false);
        var request = new MobileProactiveDTOs.ClaimRequest();
        request.version = 1;
        request.claimToken = "loser";
        MobileApiException error = assertThrows(MobileApiException.class,
                () -> service.claim(auth, "ext-1", request));
        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("PROACTIVE_CLAIM_CONFLICT", error.getErrorCode());
    }

    @Test
    void interruptedIsPersistedAsFailedNotDelivered() {
        when(proactive.updateEventStatus(any(), any())).thenReturn(event(Topic.NEWS, Map.of(
                "title", "新闻", "message", "摘要")));
        var request = new MobileProactiveDTOs.CompleteRequest();
        request.version = 1;
        request.claimToken = "claim-1";
        request.status = "interrupted";
        request.reason = "phone_call";
        assertEquals("interrupted", service.complete(auth, "ext-1", request).status());
        var captor = org.mockito.ArgumentCaptor.forClass(
                xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate.class);
        verify(proactive).updateEventStatus(org.mockito.ArgumentMatchers.eq("ext-1"), captor.capture());
        assertEquals(DeliveryStatus.FAILED, captor.getValue().getDeliveryStatus());
        assertEquals(Outcome.INTERRUPTED, captor.getValue().getOutcome());
        verify(auditDao).recordTerminal(org.mockito.ArgumentMatchers.eq("dev-mobile"),
                org.mockito.ArgumentMatchers.eq("ext-1"), org.mockito.ArgumentMatchers.eq("claim-1"),
                org.mockito.ArgumentMatchers.eq("interrupted"), org.mockito.ArgumentMatchers.eq("phone_call"),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredClaimTokenCannotWriteTerminalOrAudit() {
        when(proactive.updateEventStatus(any(), any()))
                .thenThrow(new xiaozhi.common.exception.RenException("主动事件状态已变化"));
        var request = new MobileProactiveDTOs.CompleteRequest();
        request.version = 1;
        request.claimToken = "123e4567-e89b-42d3-a456-426614174000";
        request.status = "failed";
        request.reason = "connection_error";

        MobileApiException error = assertThrows(MobileApiException.class,
                () -> service.complete(auth, "ext-1", request));
        assertEquals("PROACTIVE_TERMINAL_CONFLICT", error.getErrorCode());
        verify(auditDao, never()).recordTerminal(any(), any(), any(), any(), any(), any());
    }

    private EventView event(Topic topic, Map<String, Object> payload) {
        return new EventView("dev-mobile", instance.getMobileInstanceId(), "ext-1", topic,
                Priority.HIGH, "internal reason", topic == Topic.NEWS ? EventType.NEWS_ALERT : EventType.WEATHER_ALERT,
                payload, new Date(), Date.from(Instant.now().plusSeconds(300)), "dedupe", topic == Topic.NEWS,
                DeliveryStatus.PENDING, Outcome.NONE, null);
    }
}
