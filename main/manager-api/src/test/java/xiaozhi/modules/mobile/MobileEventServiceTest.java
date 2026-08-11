package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import xiaozhi.modules.mobile.MobileEventDTOs.BatchRequest;
import xiaozhi.modules.mobile.MobileEventDTOs.CandidateEvent;
import xiaozhi.modules.mobile.MobileEventDTOs.EventSource;

class MobileEventServiceTest {
    private MobileInstanceDao instanceDao;
    private MobileEventDao eventDao;
    private MobileEventService service;
    private MobileInstanceEntity instance;

    @BeforeEach
    void setUp() {
        instanceDao = mock(MobileInstanceDao.class);
        eventDao = mock(MobileEventDao.class);
        service = new MobileEventService(instanceDao, eventDao);
        instance = new MobileInstanceEntity();
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        instance.setInstallationId("123e4567-e89b-12d3-a456-426614174000");
        instance.setCredentialVersion(2);
        instance.setCredentialHash(MobileAssistantService.hashCredential("secret"));
        instance.setCapabilities("text_chat,notification_gateway");
        when(instanceDao.selectById(instance.getMobileInstanceId())).thenReturn(instance);
        when(eventDao.insertIgnore(any(MobileEventEntity.class))).thenReturn(1);
    }

    @Test
    void authenticatesMobileCredentialAndAcknowledgesValidEvent() {
        var result = service.accept(auth(), new BatchRequest(1, List.of(event("evt_01", "sha256:" + "a".repeat(64)))));
        assertEquals("acknowledged", result.results().get(0).status());
        org.mockito.Mockito.verify(eventDao).insertAudit(any(MobileEventAuditEntity.class));
    }

    @Test
    void acceptsStrictLocationTransitionWithoutCoordinates() {
        instance.setCapabilities("text_chat,notification_gateway,location_gateway");
        assertEquals(true, service.config(auth()).locationGateway().available());
        var result = service.accept(auth(), new BatchRequest(1, List.of(locationEvent(Map.of(
                "place_id", "place_12345678", "place_name", "公司", "transition", "enter")))));
        assertEquals("acknowledged", result.results().get(0).status());
    }

    @Test
    void rejectsLocationCoordinatesAndRequiresLocationCapability() {
        assertEquals(null, service.config(auth()).locationGateway());
        assertEquals("CAPABILITY_REQUIRED", service.accept(auth(), new BatchRequest(1, List.of(locationEvent(Map.of(
                "place_id", "place_12345678", "place_name", "公司", "transition", "enter")))))
                .results().get(0).reasonCode());
        CandidateEvent coordinates = locationEvent(Map.of(
                "place_id", "place_12345678", "place_name", "公司", "transition", "enter", "latitude", "31.2"));
        assertEquals("rejected", service.accept(auth(), new BatchRequest(1, List.of(coordinates)))
                .results().get(0).status());

        instance.setCapabilities("text_chat,notification_gateway,location_gateway");
        CandidateEvent spoofed = new CandidateEvent(1, "loc_spoof", instance.getMobileInstanceId(),
                "location.transition", Instant.now(), new EventSource("location", "android.geofence", "coordinates"),
                "entered", "已进入公司", Map.of("place_id", "place_12345678", "place_name", "公司", "transition", "enter"),
                "sha256:" + "d".repeat(64), Instant.now().plusSeconds(3600), "medium",
                Map.of("rule_id", "geofence_transition_v1"));
        assertEquals("rejected", service.accept(auth(), new BatchRequest(1, List.of(spoofed)))
                .results().get(0).status());
    }

    @Test
    void duplicateEventIsExplicitAndIdempotent() {
        when(eventDao.insertIgnore(any(MobileEventEntity.class))).thenReturn(0);
        MobileEventEntity existing = new MobileEventEntity();
        existing.setEventId("evt_01");
        when(eventDao.selectByEventId(instance.getMobileInstanceId(), "evt_01")).thenReturn(existing);

        var result = service.accept(auth(), new BatchRequest(1, List.of(event("evt_01", "sha256:" + "a".repeat(64)))));
        assertEquals("deduped", result.results().get(0).status());
        org.mockito.Mockito.verify(eventDao).insertAudit(any(MobileEventAuditEntity.class));
        org.mockito.Mockito.verify(eventDao).updateLatestState(any(MobileEventEntity.class));
    }

    @Test
    void newerRemovedStateDismissesPendingAlertCopies() {
        when(eventDao.insertIgnore(any(MobileEventEntity.class))).thenReturn(0);
        when(eventDao.updateLatestState(any(MobileEventEntity.class))).thenReturn(1);
        CandidateEvent base = event("evt_removed", "sha256:" + "f".repeat(64));
        CandidateEvent removed = new CandidateEvent(base.version(), base.eventId(),
                base.mobileInstanceId(), base.type(), base.occurredAt(), base.source(), "removed",
                base.summary(), base.entities(), base.dedupeKey(), base.expiresAt(),
                base.privacyLevel(), Map.of("rule_id", "notification_keyword_v1",
                        "transition", "updated->removed"));

        service.accept(auth(), new BatchRequest(1, List.of(removed)));

        org.mockito.Mockito.verify(eventDao).dismissPendingMobileAlerts(
                instance.getMobileInstanceId(), removed.dedupeKey());
    }

    @Test
    void rejectsExpiredAndSensitivePayloadsWithoutPersistingThem() {
        var expired = event("evt_01", "sha256:" + "a".repeat(64), Instant.now().minusSeconds(5), "安全码 123456");
        assertEquals("expired", service.accept(auth(), new BatchRequest(1, List.of(expired))).results().get(0).status());

        var sensitive = event("evt_02", "sha256:" + "b".repeat(64), Instant.now().plusSeconds(60), "验证码 123456");
        assertEquals("rejected", service.accept(auth(), new BatchRequest(1, List.of(sensitive))).results().get(0).status());
        org.mockito.Mockito.verify(eventDao, org.mockito.Mockito.times(2)).insertAudit(any(MobileEventAuditEntity.class));
    }

    @Test
    void failsClosedForSpacedPostfixedAndBearerSecretsAndAuditsEveryResult() {
        List<String> secrets = List.of("123-456 验证码", "验证码 123 456", "验证码为 123456",
                "验证码是 123456", "密码为abcd1234", "密码为p@ssw0rd", "密码是你好世界",
                "密码为a，后续", "密码为 你 好 @ +，后续", "token=abc；后续",
                "访问令牌 abc+/=", "访问令牌 abc.def-123", "Authorization Bearer abcdefghijk");
        for (int index = 0; index < secrets.size(); index++) {
            String hex = Integer.toHexString(index + 1);
            var candidate = event("secret_" + index, "sha256:" + hex.repeat(64).substring(0, 64),
                    Instant.now().plusSeconds(60), secrets.get(index));
            assertEquals("rejected", service.accept(auth(), new BatchRequest(1, List.of(candidate)))
                    .results().get(0).status());
        }
        org.mockito.Mockito.verify(eventDao, org.mockito.Mockito.times(secrets.size()))
                .insertAudit(any(MobileEventAuditEntity.class));
    }

    @Test
    void rejectsSensitiveChannelEvenWhenSummaryWasRedacted() {
        CandidateEvent base = event("channel_secret", "sha256:" + "c".repeat(64));
        CandidateEvent candidate = new CandidateEvent(base.version(), base.eventId(), base.mobileInstanceId(),
                base.type(), base.occurredAt(), new EventSource("notification", "com.example.app", "验证码为 123456"),
                base.state(), base.summary(), base.entities(), base.dedupeKey(), base.expiresAt(),
                base.privacyLevel(), base.evidence());
        assertEquals("rejected", service.accept(auth(), new BatchRequest(1, List.of(candidate)))
                .results().get(0).status());
    }

    @Test
    void acceptsOpaqueNumericNotificationMetadataWithoutTreatingItAsContent() {
        CandidateEvent base = event("numeric_metadata", "sha256:" + "9".repeat(64));
        CandidateEvent candidate = new CandidateEvent(base.version(), base.eventId(), base.mobileInstanceId(),
                base.type(), base.occurredAt(), new EventSource("notification", "com.example.app", "123456789012"),
                base.state(), base.summary(), base.entities(), base.dedupeKey(), base.expiresAt(),
                base.privacyLevel(), Map.of("rule_id", "notification_category_v1",
                        "notification_key_hash", "9".repeat(64)));
        assertEquals("acknowledged", service.accept(auth(), new BatchRequest(1, List.of(candidate)))
                .results().get(0).status());
    }

    @Test
    void rejectsMalformedNotificationMetadataInsteadOfScanningItAsContent() {
        CandidateEvent base = event("bad_metadata", "sha256:" + "8".repeat(64));
        CandidateEvent candidate = new CandidateEvent(base.version(), base.eventId(), base.mobileInstanceId(),
                base.type(), base.occurredAt(), base.source(), base.state(), base.summary(), base.entities(),
                base.dedupeKey(), base.expiresAt(), base.privacyLevel(),
                Map.of("rule_id", "notification_category_v1", "notification_key_hash", "token=secret"));
        var result = service.accept(auth(), new BatchRequest(1, List.of(candidate))).results().get(0);
        assertEquals("rejected", result.status());
        assertEquals("INVALID_EVENT_SHAPE", result.reasonCode());
    }

    @Test
    void rejectsUnknownProtocolVersionBeforeReadingEvents() {
        var invalid = new MobileEventService.MobileAuth(instance.getMobileInstanceId(), instance.getInstallationId(), 2,
                2, "secret");
        MobileApiException error = assertThrows(MobileApiException.class, () -> service.config(invalid));
        assertEquals(401, error.getStatus().value());
    }

    @Test
    void invalidMobileTokenReturnsReal401() {
        MobileApiException error = assertThrows(MobileApiException.class,
                () -> service.config(new MobileEventService.MobileAuth(instance.getMobileInstanceId(),
                        instance.getInstallationId(), 2, 1, "wrong")));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
    }

    private MobileEventService.MobileAuth auth() {
        return new MobileEventService.MobileAuth(instance.getMobileInstanceId(), instance.getInstallationId(), 2, 1,
                "secret");
    }

    private CandidateEvent event(String id, String dedupe) {
        return event(id, dedupe, Instant.now().plusSeconds(3600), "包裹状态发生变化");
    }

    private CandidateEvent event(String id, String dedupe, Instant expiresAt, String summary) {
        return new CandidateEvent(1, id, instance.getMobileInstanceId(), "notification.state_changed",
                Instant.now(), new EventSource("notification", "com.example.app", "delivery"), "updated",
                summary, Map.of("category", "parcel"), dedupe, expiresAt, "medium",
                Map.of("rule_id", "notification_keyword_v1", "transition", "posted->updated"));
    }

    private CandidateEvent locationEvent(Map<String, String> entities) {
        return new CandidateEvent(1, "loc_01", instance.getMobileInstanceId(), "location.transition",
                Instant.now(), new EventSource("location", "android.geofence", null), "entered",
                "已进入公司", entities, "sha256:" + "e".repeat(64), Instant.now().plusSeconds(3600), "medium",
                Map.of("rule_id", "geofence_transition_v1"));
    }
}
