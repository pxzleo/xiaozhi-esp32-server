package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventClaim;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveDTOs.HabitObserve;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.DedupePolicy;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.HabitType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

class ProactiveServiceTest {
    private DeviceDao deviceDao;
    private ProactivePreferenceDao preferenceDao;
    private ProactiveEventDao eventDao;
    private ProactiveEventDedupeDao eventDedupeDao;
    private ProactiveGlobalDao globalDao;
    private ProactiveHabitDao habitDao;
    private ProactiveService service;
    private DeviceEntity device;

    @BeforeEach
    void setUp() {
        deviceDao = mock(DeviceDao.class);
        preferenceDao = mock(ProactivePreferenceDao.class);
        eventDao = mock(ProactiveEventDao.class);
        eventDedupeDao = mock(ProactiveEventDedupeDao.class);
        globalDao = mock(ProactiveGlobalDao.class);
        habitDao = mock(ProactiveHabitDao.class);
        service = new ProactiveService(deviceDao, preferenceDao, eventDao, eventDedupeDao,
                globalDao, habitDao, new ObjectMapper());
        device = new DeviceEntity();
        device.setId("device-1");
        device.setMacAddress("11:22:33:44:55:66");
        device.setUserId(7L);
        when(deviceDao.selectList(any())).thenReturn(List.of(device));
        when(deviceDao.selectById("device-1")).thenReturn(device);
        when(deviceDao.selectByIdForUpdate("device-1")).thenReturn(device);
        when(globalDao.selectExternalMonitoringValueForUpdate()).thenReturn("true");
        when(eventDedupeDao.selectLastCreatedAt(any(), any(), any())).thenReturn(new Date());
    }

    @Test
    void normalizesLegacyAggressivePreferenceToUnlimited() {
        ProactivePreferenceEntity preference = preference(Mode.AGGRESSIVE, 5);
        ProactivePreferenceEntity normalized = preference(Mode.AGGRESSIVE, 0);
        when(preferenceDao.selectById("device-1")).thenReturn(preference);
        when(preferenceDao.normalizeLegacyAggressiveLimit(eq("device-1"), eq(5), eq(0), any()))
                .thenReturn(1);
        when(preferenceDao.selectByIdForUpdate("device-1")).thenReturn(normalized);

        var view = service.getPreferenceByMac(device.getMacAddress());

        assertEquals(Mode.AGGRESSIVE, view.mode());
        assertEquals(0, view.dailyLimit());
        assertEquals(null, view.quietStart());
        verify(preferenceDao).insertDefault(eq("device-1"), eq(device.getMacAddress()), any());
        verify(preferenceDao).normalizeLegacyAggressiveLimit(eq("device-1"), eq(5), eq(0), any());
        verify(preferenceDao, never()).updateById(preference);
    }

    @Test
    void normalizesLegacyAggressiveLimitSavedForTodaySilentRestore() {
        ProactivePreferenceEntity preference = preference(Mode.TODAY_SILENT, 0);
        preference.setPreviousMode(Mode.AGGRESSIVE.name());
        preference.setPreviousDailyLimit(4);
        ProactivePreferenceEntity normalized = preference(Mode.TODAY_SILENT, 0);
        normalized.setPreviousMode(Mode.AGGRESSIVE.name());
        normalized.setPreviousDailyLimit(0);
        when(preferenceDao.selectById("device-1")).thenReturn(preference);
        when(preferenceDao.normalizeLegacyPreviousAggressiveLimit(
                eq("device-1"), eq(4), eq(0), any())).thenReturn(1);
        when(preferenceDao.selectByIdForUpdate("device-1")).thenReturn(normalized);

        var view = service.getPreferenceByMac(device.getMacAddress());

        assertEquals(0, view.previousDailyLimit());
        verify(preferenceDao).normalizeLegacyPreviousAggressiveLimit(
                eq("device-1"), eq(4), eq(0), any());
        verify(preferenceDao, never()).updateById(preference);
    }

    @Test
    void concurrentPreferenceUpdateWinsOverLegacyNormalizationWithoutBeingOverwritten() {
        ProactivePreferenceEntity legacy = preference(Mode.AGGRESSIVE, 5);
        ProactivePreferenceEntity concurrent = preference(Mode.ACTIVE, 4);
        concurrent.setVersion(1);
        when(preferenceDao.selectById("device-1")).thenReturn(legacy);
        when(preferenceDao.normalizeLegacyAggressiveLimit(
                eq("device-1"), eq(5), eq(0), any())).thenReturn(0);
        when(preferenceDao.selectByIdForUpdate("device-1")).thenReturn(concurrent);

        var view = service.getPreferenceByMac(device.getMacAddress());

        assertEquals(Mode.ACTIVE, view.mode());
        assertEquals(4, view.dailyLimit());
        verify(preferenceDao, never()).updateById(any(ProactivePreferenceEntity.class));
    }

    @Test
    void retriesChangedLegacyVersionAndReturnsAfterSecondCasSucceeds() {
        ProactivePreferenceEntity initial = preference(Mode.AGGRESSIVE, 5);
        ProactivePreferenceEntity concurrentlyChanged = preference(Mode.AGGRESSIVE, 4);
        concurrentlyChanged.setVersion(1);
        ProactivePreferenceEntity normalized = preference(Mode.AGGRESSIVE, 0);
        normalized.setVersion(2);
        when(preferenceDao.selectById("device-1")).thenReturn(initial);
        when(preferenceDao.normalizeLegacyAggressiveLimit(
                eq("device-1"), eq(5), eq(0), any())).thenReturn(0);
        when(preferenceDao.normalizeLegacyAggressiveLimit(
                eq("device-1"), eq(4), eq(1), any())).thenReturn(1);
        when(preferenceDao.selectByIdForUpdate("device-1"))
                .thenReturn(concurrentlyChanged, normalized);

        var view = service.getPreferenceByMac(device.getMacAddress());

        assertEquals(Mode.AGGRESSIVE, view.mode());
        assertEquals(0, view.dailyLimit());
        assertEquals(2, view.version());
        verify(preferenceDao).normalizeLegacyAggressiveLimit(
                eq("device-1"), eq(5), eq(0), any());
        verify(preferenceDao).normalizeLegacyAggressiveLimit(
                eq("device-1"), eq(4), eq(1), any());
        verify(preferenceDao, times(2)).selectByIdForUpdate("device-1");
    }

    @Test
    void activeUsesFiveWhenDailyLimitIsOmitted() {
        ProactivePreferenceEntity preference = preference(Mode.CONSERVATIVE, 1);
        when(preferenceDao.selectById("device-1")).thenReturn(preference);
        when(preferenceDao.updateById(preference)).thenReturn(1);
        PreferenceUpdate request = new PreferenceUpdate();
        request.setMode(Mode.ACTIVE);

        var view = service.updatePreference(7L, "device-1", request);

        assertEquals(Mode.ACTIVE, view.mode());
        assertEquals(5, view.dailyLimit());
    }

    @Test
    void preservesCustomizedActiveLimitForTodaySilent() {
        ProactivePreferenceEntity preference = preference(Mode.ACTIVE, 2);
        when(preferenceDao.selectById("device-1")).thenReturn(preference);
        when(preferenceDao.updateById(any(ProactivePreferenceEntity.class))).thenReturn(1);
        PreferenceUpdate request = new PreferenceUpdate();
        request.setMode(Mode.TODAY_SILENT);

        var view = service.updatePreference(7L, "device-1", request);

        assertEquals(Mode.TODAY_SILENT, view.mode());
        assertEquals(Mode.ACTIVE, view.previousMode());
        assertEquals(2, view.previousDailyLimit());
        assertEquals(0, view.dailyLimit());
        assertTrue(view.silentUntil().after(new Date()));
    }

    @Test
    void restoresExactActiveTwoLimitAfterTodaySilentExpires() {
        ProactivePreferenceEntity preference = preference(Mode.TODAY_SILENT, 0);
        preference.setPreviousMode(Mode.ACTIVE.name());
        preference.setPreviousDailyLimit(2);
        when(preferenceDao.restoreExpiredSilent(eq("device-1"), any())).thenAnswer(invocation -> {
            preference.setMode(preference.getPreviousMode());
            preference.setDailyLimit(preference.getPreviousDailyLimit());
            preference.setPreviousMode(null);
            preference.setPreviousDailyLimit(null);
            return 1;
        });
        when(preferenceDao.selectById("device-1")).thenReturn(preference);

        var view = service.getPreferenceByMac(device.getMacAddress());

        assertEquals(Mode.ACTIVE, view.mode());
        assertEquals(2, view.dailyLimit());
    }

    @Test
    void deniesOtherUsersWithoutEnumeratingTheirDeviceData() {
        RenException exception = assertThrows(RenException.class,
                () -> service.getPreference(8L, "device-1"));
        assertEquals("设备不存在", exception.getMsg());
    }

    @Test
    void eventUpsertIsIdempotentAndResponseContainsParsedControlledJson() {
        EventUpsert request = eventRequest();
        ProactiveEventEntity stored = eventEntity(request);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", request.getEventId()))
                .thenReturn(stored);

        var view = service.upsertEvent(request);

        verify(eventDao, never()).insertIfAbsent(any(ProactiveEventEntity.class));
        verify(deviceDao).selectByIdForUpdate("device-1");
        assertEquals("hello", view.payload().get("message"));
        assertFalse(view.payload().containsKey("reasoning"));
    }

    @Test
    void concurrentIdenticalInsertNoOpReadsAuthoritativeRowAndSucceeds() {
        EventUpsert request = eventRequest();
        ProactiveEventEntity authoritative = eventEntity(request);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(null, authoritative);

        var view = service.upsertEvent(request);

        assertEquals("event-1", view.eventId());
        verify(eventDao).insertIfAbsent(any(ProactiveEventEntity.class));
        verify(eventDao, times(2)).selectByDeviceAndEventIdForUpdate("device-1", "event-1");
    }

    @Test
    void rejectsUncontrolledPayloadKeys() {
        EventUpsert request = eventRequest();
        request.setPayload(Map.of("chain_of_thought", "must not persist"));
        assertThrows(RenException.class, () -> service.upsertEvent(request));
    }

    @Test
    void eventPayloadRejectsWrongTypeAndInvalidScheduledTime() {
        EventUpsert wrongType = eventRequest();
        wrongType.setPayload(Map.of("message", true));
        EventUpsert invalidTime = eventRequest();
        invalidTime.setPayload(Map.of("scheduled_at", "2026-99-40 25:61"));
        EventUpsert oversized = eventRequest();
        oversized.setPayload(Map.of(
                "title", "t".repeat(100),
                "message", "m".repeat(300),
                "reference_id", "r".repeat(128)));

        assertThrows(RenException.class, () -> service.upsertEvent(wrongType));
        assertThrows(RenException.class, () -> service.upsertEvent(invalidTime));
        assertThrows(RenException.class, () -> service.upsertEvent(oversized));
    }

    @Test
    void eventPayloadAcceptsStringsAndIsoLocalDateTime() {
        EventUpsert request = eventRequest();
        request.setPayload(Map.of("message", "hello", "scheduled_at", "2026-08-08T09:30:00"));
        ProactiveEventEntity stored = eventEntity(request);
        stored.setPayload("{\"message\":\"hello\",\"scheduled_at\":\"2026-08-08T09:30:00\"}");
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1")).thenReturn(stored);

        assertEquals("2026-08-08T09:30:00", service.upsertEvent(request).payload().get("scheduled_at"));
    }

    @Test
    void newsEventPreservesLongHttpsReferenceUrlForAuthoritativeRead() throws Exception {
        Date ledgerTime = new Date(1_786_248_740_000L);
        String referenceUrl = "https://news.example.com/article?context=" + "a".repeat(1_600);
        EventUpsert request = eventRequest();
        request.setTopic(Topic.NEWS);
        request.setEventType(EventType.NEWS_ALERT);
        request.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        request.setDedupeWindowHours(24);
        request.setPayload(Map.of("message", "重大新闻", "reference_url", referenceUrl));
        AtomicReference<ProactiveEventEntity> inserted = new AtomicReference<>();
        when(eventDedupeDao.selectForUpdate(eq("device-1"), eq("NEWS_ALERT"), any()))
                .thenReturn(new ProactiveEventDedupeEntity());
        when(eventDedupeDao.markCreated(eq("device-1"), eq("NEWS_ALERT"), any(), any()))
                .thenReturn(1);
        when(eventDedupeDao.selectLastCreatedAt(eq("device-1"), eq("NEWS_ALERT"), any()))
                .thenReturn(ledgerTime);
        when(eventDao.selectByDeviceAndEventIdForUpdate(eq("device-1"), any()))
                .thenAnswer(ignored -> inserted.get());
        when(eventDao.insertIfAbsent(any(ProactiveEventEntity.class))).thenAnswer(call -> {
            inserted.set(call.getArgument(0));
            return 1;
        });
        when(eventDao.selectMonitorEventByMacAndEventId(eq(device.getMacAddress()), any()))
                .thenAnswer(ignored -> inserted.get());

        var created = service.createMonitorEvent(request);
        assertEquals(ledgerTime, created.dedupeRecordedAt());
        assertEquals(referenceUrl, created.event().payload().get("reference_url"));
        verify(eventDao).insertIfAbsent(argThat(event -> event.getPayload().contains(referenceUrl)));
        assertEquals(referenceUrl,
                service.monitorEvent(device.getMacAddress(), created.authoritativeEventId())
                        .payload().get("reference_url"));
    }

    @Test
    void legacyReferenceIdRemainsAccepted() {
        EventUpsert request = eventRequest();
        request.setPayload(Map.of("reference_id", "source-item-123"));
        ProactiveEventEntity stored = eventEntity(request);
        stored.setPayload("{\"reference_id\":\"source-item-123\"}");
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1")).thenReturn(stored);

        assertEquals("source-item-123", service.upsertEvent(request).payload().get("reference_id"));
    }

    @Test
    void newsReferenceUrlRejectsUnsafeSchemesCredentialsAndExcessLength() {
        List<String> invalidUrls = List.of(
                "javascript:alert(1)",
                "https://user:password@news.example.com/article",
                "https://news.example.com/" + "a".repeat(2_049));

        for (String referenceUrl : invalidUrls) {
            EventUpsert request = eventRequest();
            request.setPayload(Map.of("reference_url", referenceUrl));
            assertThrows(RenException.class, () -> service.upsertEvent(request));
        }
    }

    @Test
    void concurrentRollingNewsCreatesOnceAndReturnsAuthoritativeEventDespitePayloadChanges() throws Exception {
        EventUpsert first = rollingNewsRequest("news-event-1", "第一版摘要");
        EventUpsert second = rollingNewsRequest("news-event-2", "模型变化后的摘要");
        ReentrantLock ledgerLock = new ReentrantLock();
        AtomicReference<String> recentEventId = new AtomicReference<>();
        Map<String, ProactiveEventEntity> stored = new ConcurrentHashMap<>();
        when(eventDedupeDao.selectForUpdate(eq("device-1"), eq("NEWS_ALERT"), any()))
                .thenAnswer(ignored -> {
                    ledgerLock.lock();
                    return new ProactiveEventDedupeEntity();
                });
        when(eventDedupeDao.selectRecentEventId(eq("device-1"), eq("NEWS_ALERT"), any(), eq(24)))
                .thenAnswer(ignored -> {
                    String value = recentEventId.get();
                    if (value != null) ledgerLock.unlock();
                    return value;
                });
        when(eventDedupeDao.markCreated(eq("device-1"), eq("NEWS_ALERT"), any(), any()))
                .thenAnswer(call -> {
                    recentEventId.set(call.getArgument(3));
                    ledgerLock.unlock();
                    return 1;
                });
        when(eventDao.selectByDeviceAndEventIdForUpdate(eq("device-1"), any()))
                .thenAnswer(call -> stored.get(call.getArgument(1)));
        when(eventDao.insertIfAbsent(any(ProactiveEventEntity.class))).thenAnswer(call -> {
            ProactiveEventEntity entity = call.getArgument(0);
            stored.put(entity.getEventId(), entity);
            return 1;
        });
        when(eventDao.selectByDeviceAndEventId(eq("device-1"), any()))
                .thenAnswer(call -> stored.get(call.getArgument(1)));

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(() -> service.createMonitorEvent(first));
            var secondResult = executor.submit(() -> service.createMonitorEvent(second));
            var left = firstResult.get();
            var right = secondResult.get();
            assertEquals(1, List.of(left, right).stream().filter(result -> result.created()).count());
            assertEquals(1, List.of(left, right).stream().filter(result -> result.deduped()).count());
            assertEquals(left.authoritativeEventId(), right.authoritativeEventId());
        }
        verify(eventDao, times(1)).insertIfAbsent(any(ProactiveEventEntity.class));
        verify(eventDedupeDao, times(1)).markCreated(eq("device-1"), eq("NEWS_ALERT"),
                argThat(hash -> hash.length() == 64 && !hash.contains("news-cluster")), any());
    }

    @Test
    void externalDedupePoliciesAndKeysAreStrictWhileLegacyReminderRemainsCompatible() {
        EventUpsert news = rollingNewsRequest("news-event", "摘要");
        news.setDedupeWindowHours(12);
        assertThrows(RenException.class, () -> service.createMonitorEvent(news));

        EventUpsert weather = eventRequest();
        weather.setTopic(Topic.WEATHER);
        weather.setEventType(EventType.WEATHER_ALERT);
        weather.setDedupeKey("https://must-not-be-a-key.example/title");
        weather.setDedupePolicy(DedupePolicy.EVENT_ID);
        assertThrows(RenException.class, () -> service.createMonitorEvent(weather));

        EventUpsert reminder = eventRequest();
        ProactiveEventEntity stored = eventEntity(reminder);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1")).thenReturn(stored);
        assertEquals("event-1", service.upsertEvent(reminder).eventId());
    }

    @Test
    void globalSwitchIsLockedBeforeExternalEventCreation() {
        when(globalDao.selectExternalMonitoringValueForUpdate()).thenReturn("false");

        assertThrows(RenException.class,
                () -> service.createMonitorEvent(rollingNewsRequest("news-event", "摘要")));

        verify(eventDedupeDao, never()).insertIfAbsent(any(), any(), any());
        verify(eventDao, never()).insertIfAbsent(any(ProactiveEventEntity.class));
    }

    @Test
    void invalidLockedGlobalSwitchValueFailsExplicitly() {
        when(globalDao.selectExternalMonitoringValueForUpdate()).thenReturn("enabled");

        RenException error = assertThrows(RenException.class,
                () -> service.createMonitorEvent(rollingNewsRequest("news-event", "摘要")));

        assertEquals("外界监测全局开关系统参数无效", error.getMessage());
        verify(eventDedupeDao, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void monitorAndLegacyEventEndpointsHaveDisjointEventTypes() {
        EventUpsert reminder = eventRequest();
        EventUpsert news = rollingNewsRequest("news-event", "摘要");

        assertThrows(RenException.class, () -> service.createMonitorEvent(reminder));
        assertThrows(RenException.class, () -> service.upsertEvent(news));

        verify(globalDao, never()).selectExternalMonitoringValueForUpdate();
        verify(eventDao, never()).insertIfAbsent(any(ProactiveEventEntity.class));
    }

    @Test
    void expiredRollingWindowCreatesFreshAuthoritativeIdEvenIfCallerIdIsReused() {
        EventUpsert request = rollingNewsRequest("caller-stable-id", "摘要");
        Map<String, ProactiveEventEntity> stored = new ConcurrentHashMap<>();
        when(eventDedupeDao.selectForUpdate(eq("device-1"), eq("NEWS_ALERT"), any()))
                .thenReturn(new ProactiveEventDedupeEntity());
        when(eventDedupeDao.selectRecentEventId(eq("device-1"), eq("NEWS_ALERT"), any(), eq(24)))
                .thenReturn(null);
        when(eventDedupeDao.markCreated(eq("device-1"), eq("NEWS_ALERT"), any(), any()))
                .thenReturn(1);
        when(eventDao.selectByDeviceAndEventIdForUpdate(eq("device-1"), any()))
                .thenAnswer(call -> stored.get(call.getArgument(1)));
        when(eventDao.insertIfAbsent(any(ProactiveEventEntity.class))).thenAnswer(call -> {
            ProactiveEventEntity entity = call.getArgument(0);
            stored.put(entity.getEventId(), entity);
            return 1;
        });

        var first = service.createMonitorEvent(request);
        var afterWindow = service.createMonitorEvent(request);

        assertTrue(first.created());
        assertTrue(afterWindow.created());
        assertFalse(first.authoritativeEventId().equals(afterWindow.authoritativeEventId()));
        assertFalse(first.authoritativeEventId().equals(request.getEventId()));
    }

    @Test
    void rollingLedgerIsScopedByDeviceAndDedupeKey() {
        DeviceEntity secondDevice = new DeviceEntity();
        secondDevice.setId("device-2");
        secondDevice.setMacAddress("22:33:44:55:66:77");
        secondDevice.setUserId(8L);
        EventUpsert first = rollingNewsRequest("caller-1", "摘要一");
        EventUpsert second = rollingNewsRequest("caller-2", "摘要二");
        second.setMacAddress(secondDevice.getMacAddress());
        second.setDedupeKey("news-cluster-fedcba9876543210");
        Map<String, String> hashes = new ConcurrentHashMap<>();
        Map<String, ProactiveEventEntity> stored = new ConcurrentHashMap<>();
        when(deviceDao.selectList(any())).thenReturn(List.of(device), List.of(secondDevice));
        when(deviceDao.selectByIdForUpdate("device-2")).thenReturn(secondDevice);
        when(eventDedupeDao.insertIfAbsent(any(), eq("NEWS_ALERT"), any()))
                .thenAnswer(call -> {
                    hashes.put(call.getArgument(0), call.getArgument(2));
                    return 1;
                });
        when(eventDedupeDao.selectForUpdate(any(), eq("NEWS_ALERT"), any()))
                .thenReturn(new ProactiveEventDedupeEntity());
        when(eventDedupeDao.markCreated(any(), eq("NEWS_ALERT"), any(), any())).thenReturn(1);
        when(eventDao.selectByDeviceAndEventIdForUpdate(any(), any()))
                .thenAnswer(call -> stored.get(call.getArgument(0) + ":" + call.getArgument(1)));
        when(eventDao.insertIfAbsent(any(ProactiveEventEntity.class))).thenAnswer(call -> {
            ProactiveEventEntity entity = call.getArgument(0);
            stored.put(entity.getDeviceId() + ":" + entity.getEventId(), entity);
            return 1;
        });

        var firstResult = service.createMonitorEvent(first);
        var secondResult = service.createMonitorEvent(second);

        assertTrue(firstResult.created());
        assertTrue(secondResult.created());
        assertEquals(2, hashes.size());
        assertFalse(hashes.get("device-1").equals(hashes.get("device-2")));
    }

    @Test
    void habitPayloadRejectsInvalidEnumsTypesModesAndTimes() {
        List<Map<String, Object>> invalidPayloads = List.of(
                Map.of("topic", "MUSIC"),
                Map.of("topic", "unknown"),
                Map.of("suggested_mode", "today_silent"),
                Map.of("description", 123),
                Map.of("suggested_time", "24:00"));

        for (Map<String, Object> payload : invalidPayloads) {
            assertThrows(RenException.class, () -> service.observeHabit(habitRequest(payload)));
        }
    }

    @Test
    void habitPayloadAcceptsControlledSemanticValues() {
        Map<String, Object> payload = Map.of(
                "description", "工作日九点播放音乐",
                "topic", "music",
                "suggested_mode", "active",
                "suggested_time", "09:00");
        ProactiveHabitEntity stored = new ProactiveHabitEntity();
        stored.setId(1L);
        stored.setDeviceId("device-1");
        stored.setMacAddress(device.getMacAddress());
        stored.setHabitType(HabitType.TIME_PATTERN.name());
        stored.setHabitKey("weekday-music");
        stored.setEvidenceCount(3);
        stored.setFirstSeenAt(new Date());
        stored.setLastSeenAt(new Date());
        stored.setSuggested(true);
        stored.setAccepted(false);
        stored.setDismissed(false);
        stored.setPayload("{\"description\":\"工作日九点播放音乐\",\"topic\":\"music\","
                + "\"suggested_mode\":\"active\",\"suggested_time\":\"09:00\"}");
        when(habitDao.selectOne(any())).thenReturn(stored);

        var view = service.observeHabit(habitRequest(payload));

        assertEquals("music", view.payload().get("topic"));
        verify(habitDao).observeAtomic(any(ProactiveHabitEntity.class));
    }

    @Test
    void statusUpdateUsesBothResolvedDeviceAndEventId() {
        EventStatusUpdate update = new EventStatusUpdate();
        update.setMacAddress(device.getMacAddress());
        update.setDeliveryStatus(DeliveryStatus.DELIVERED);
        update.setOutcome(Outcome.ACKNOWLEDGED);
        ProactiveEventEntity pending = eventEntity(eventRequest());
        ProactiveEventEntity delivered = eventEntity(eventRequest());
        delivered.setDeliveryStatus(DeliveryStatus.DELIVERED.name());
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(pending);
        when(eventDao.updateStatusCas(eq("device-1"), eq("event-1"), eq("PENDING"),
                eq(null), eq("DELIVERED"), eq("ACKNOWLEDGED"), any())).thenReturn(1);
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenReturn(delivered);

        service.updateEventStatus("event-1", update);

        verify(eventDao).updateStatusCas(eq("device-1"), eq("event-1"), eq("PENDING"),
                eq(null), eq("DELIVERED"), eq("ACKNOWLEDGED"), any());
    }

    @Test
    void pendingEventCanBeDismissedWhenCurrentPolicySuppressesDelivery() {
        EventStatusUpdate update = new EventStatusUpdate();
        update.setMacAddress(device.getMacAddress());
        update.setDeliveryStatus(DeliveryStatus.DISMISSED);
        update.setOutcome(Outcome.DISMISSED);
        ProactiveEventEntity pending = eventEntity(eventRequest());
        ProactiveEventEntity dismissed = eventEntity(eventRequest());
        dismissed.setDeliveryStatus(DeliveryStatus.DISMISSED.name());
        dismissed.setOutcome(Outcome.DISMISSED.name());
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(pending);
        when(eventDao.updateStatusCas(eq("device-1"), eq("event-1"), eq("PENDING"),
                eq(null), eq("DISMISSED"), eq("DISMISSED"), any())).thenReturn(1);
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenReturn(dismissed);

        var result = service.updateEventStatus("event-1", update);

        assertEquals(DeliveryStatus.DISMISSED, result.deliveryStatus());
        assertEquals(Outcome.DISMISSED, result.outcome());
    }

    @Test
    void claimedEventCannotBeDismissedEvenWithItsClaimToken() {
        EventStatusUpdate update = new EventStatusUpdate();
        update.setMacAddress(device.getMacAddress());
        update.setDeliveryStatus(DeliveryStatus.DISMISSED);
        update.setOutcome(Outcome.DISMISSED);
        update.setClaimToken("active-token");
        ProactiveEventEntity claimed = eventEntity(eventRequest());
        claimed.setDeliveryStatus(DeliveryStatus.CLAIMED.name());
        claimed.setClaimToken("active-token");
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(claimed);

        assertThrows(RenException.class, () -> service.updateEventStatus("event-1", update));

        verify(eventDao, never()).updateStatusCas(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void dismissedStatusRequiresDismissedOutcome() {
        EventStatusUpdate update = new EventStatusUpdate();
        update.setMacAddress(device.getMacAddress());
        update.setDeliveryStatus(DeliveryStatus.DISMISSED);
        update.setOutcome(Outcome.NONE);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(eventEntity(eventRequest()));

        assertThrows(RenException.class, () -> service.updateEventStatus("event-1", update));

        verify(eventDao, never()).updateStatusCas(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentEventClaimHasExactlyOneServiceWinner() throws Exception {
        EventClaim first = claim("token-1");
        EventClaim second = claim("token-2");
        AtomicReference<String> owner = new AtomicReference<>();
        when(eventDao.claimPending(eq("device-1"), eq("event-1"), any(), any(), any()))
                .thenAnswer(call -> owner.compareAndSet(null, call.getArgument(2)) ? 1 : 0);
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenAnswer(ignored -> {
            ProactiveEventEntity event = eventEntity(eventRequest());
            event.setDeliveryStatus(DeliveryStatus.CLAIMED.name());
            event.setClaimToken(owner.get());
            return event;
        });

        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = executor.invokeAll(List.of(
                    () -> service.claimEvent("event-1", first),
                    () -> service.claimEvent("event-1", second)));
            long winners = results.stream().filter(result -> {
                try {
                    return Boolean.TRUE.equals(result.get());
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).count();
            assertEquals(1, winners);
        }
    }

    @Test
    void lostClaimResponseCanRetryWithSameToken() {
        EventClaim claim = claim("same-token");
        ProactiveEventEntity claimed = eventEntity(eventRequest());
        claimed.setDeliveryStatus(DeliveryStatus.CLAIMED.name());
        claimed.setClaimToken("same-token");
        when(eventDao.claimPending(eq("device-1"), eq("event-1"), eq("same-token"), any(), any()))
                .thenReturn(1);
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenReturn(claimed);

        assertTrue(service.claimEvent("event-1", claim));
        assertTrue(service.claimEvent("event-1", claim));
    }

    @Test
    void staleLeaseCanBeReclaimedByDifferentToken() {
        EventClaim claim = claim("new-token");
        ProactiveEventEntity reclaimed = eventEntity(eventRequest());
        reclaimed.setDeliveryStatus(DeliveryStatus.CLAIMED.name());
        reclaimed.setClaimToken("new-token");
        when(eventDao.claimPending(eq("device-1"), eq("event-1"), eq("new-token"), any(), any()))
                .thenReturn(1);
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenReturn(reclaimed);

        assertTrue(service.claimEvent("event-1", claim));
        verify(eventDao).claimPending(eq("device-1"), eq("event-1"), eq("new-token"),
                any(), any());
    }

    @Test
    void cachedPendingMonitorEventCannotBeClaimedAfterMonitorIsDisabled() {
        for (Priority priority : List.of(Priority.NORMAL, Priority.CRITICAL)) {
            EventClaim claim = claim("cached-" + priority.name().toLowerCase());
            when(eventDao.claimPending(eq("device-1"), eq("event-1"),
                    eq(claim.getClaimToken()), any(), any())).thenReturn(0);

            assertFalse(service.claimEvent("event-1", claim));
        }

        verify(eventDao, never()).selectByDeviceAndEventId("device-1", "event-1");
    }

    @Test
    void authoritativeMonitorReadRejectsEventAfterMonitorIsDisabled() {
        when(eventDao.selectMonitorEventByMacAndEventId(device.getMacAddress(), "event-1"))
                .thenReturn(null);

        assertThrows(RenException.class,
                () -> service.monitorEvent(device.getMacAddress(), "event-1"));
    }

    @Test
    void oldClaimTokenCannotCompleteReclaimedEvent() {
        ProactiveEventEntity claimed = eventEntity(eventRequest());
        claimed.setDeliveryStatus(DeliveryStatus.CLAIMED.name());
        claimed.setClaimToken("new-token");
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(claimed);
        EventStatusUpdate update = status(DeliveryStatus.DELIVERED, "old-token");

        assertThrows(RenException.class, () -> service.updateEventStatus("event-1", update));
        verify(eventDao, never()).updateStatusCas(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void oldClaimTokenCannotUpdateOutcomeAfterNewClaimDelivered() {
        ProactiveEventEntity delivered = eventEntity(eventRequest());
        delivered.setDeliveryStatus(DeliveryStatus.DELIVERED.name());
        delivered.setClaimToken("new-token");
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(delivered);

        assertThrows(RenException.class, () -> service.updateEventStatus(
                "event-1", status(DeliveryStatus.DELIVERED, "old-token")));
        verify(eventDao, never()).updateStatusCas(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void terminalEventCannotRegressOrBeClaimedDirectly() {
        ProactiveEventEntity delivered = eventEntity(eventRequest());
        delivered.setDeliveryStatus(DeliveryStatus.DELIVERED.name());
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1"))
                .thenReturn(delivered);

        assertThrows(RenException.class, () -> service.updateEventStatus(
                "event-1", status(DeliveryStatus.FAILED, null)));
        assertThrows(RenException.class, () -> service.updateEventStatus(
                "event-1", status(DeliveryStatus.CLAIMED, null)));
    }

    @Test
    void rejectsSameDeviceEventIdWhenAnyAuditedFieldDiffers() {
        EventUpsert request = eventRequest();
        ProactiveEventEntity stored = eventEntity(request);
        stored.setReason("different reason");
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1")).thenReturn(stored);

        RenException error = assertThrows(RenException.class, () -> service.upsertEvent(request));

        assertEquals("event_id已存在但事件内容不一致", error.getMsg());
        verify(eventDao, never()).insertIfAbsent(any(ProactiveEventEntity.class));
    }

    @Test
    void sameEventIdCanBeAuditedByTwoDifferentDevices() {
        DeviceEntity other = new DeviceEntity();
        other.setId("device-2");
        other.setMacAddress("AA:BB:CC:DD:EE:FF");
        other.setUserId(8L);
        when(deviceDao.selectByIdForUpdate("device-2")).thenReturn(other);
        EventUpsert first = eventRequest();
        EventUpsert second = eventRequest();
        second.setMacAddress(other.getMacAddress());
        ProactiveEventEntity firstStored = eventEntity(first);
        ProactiveEventEntity secondStored = eventEntity(second);
        secondStored.setDeviceId(other.getId());
        secondStored.setMacAddress(other.getMacAddress());
        when(deviceDao.selectList(any())).thenReturn(List.of(device), List.of(other));
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "event-1")).thenReturn(null, firstStored);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-2", "event-1")).thenReturn(null, secondStored);

        service.upsertEvent(first);
        service.upsertEvent(second);

        verify(eventDao, times(2)).insertIfAbsent(any(ProactiveEventEntity.class));
    }

    @Test
    void sameDedupeKeyAllowsFaultAndRecoveryAsSeparateAuditEvents() {
        EventUpsert fault = eventRequest();
        fault.setEventId("fault-1");
        fault.setDedupeKey("music-login");
        fault.setEventType(EventType.MUSIC_STATUS);
        EventUpsert recovery = eventRequest();
        recovery.setEventId("recovery-1");
        recovery.setDedupeKey("music-login");
        recovery.setEventType(EventType.SYSTEM);
        ProactiveEventEntity faultStored = eventEntity(fault);
        ProactiveEventEntity recoveryStored = eventEntity(recovery);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "fault-1")).thenReturn(null, faultStored);
        when(eventDao.selectByDeviceAndEventIdForUpdate("device-1", "recovery-1")).thenReturn(null, recoveryStored);

        service.upsertEvent(fault);
        service.upsertEvent(recovery);

        verify(eventDao, times(2)).insertIfAbsent(any(ProactiveEventEntity.class));
    }

    @Test
    void rejectsDuplicateMacInsteadOfSelectingArbitraryDevice() {
        DeviceEntity duplicate = new DeviceEntity();
        duplicate.setId("device-duplicate");
        duplicate.setMacAddress(device.getMacAddress());
        when(deviceDao.selectList(any())).thenReturn(List.of(device, duplicate));

        RenException error = assertThrows(RenException.class,
                () -> service.getPreferenceByMac(device.getMacAddress()));

        assertEquals("MAC对应多设备", error.getMsg());
    }

    @Test
    void rejectsExtremePageBeforeOffsetCanOverflow() {
        assertThrows(RenException.class,
                () -> service.events(7L, null, null, null, null, 1_001, 100));
        verify(eventDao, never()).pageForUser(any(), any(), any(), any(), any(), anyInt(), anyLong());
    }

    @Test
    void paginationWithoutDeviceFilterRemainsScopedToCurrentUser() {
        when(eventDao.pageForUser(eq(7L), eq(null), eq(null), eq(null), eq(null),
                eq(20), eq(20))).thenReturn(List.of());
        when(eventDao.countForUser(7L, null, null, null, null)).thenReturn(0L);

        var page = service.events(7L, null, null, null, null, 2, 20);

        assertEquals(0, page.getTotal());
        verify(eventDao).pageForUser(7L, null, null, null, null, 20, 20);
    }

    private ProactivePreferenceEntity preference(Mode mode, int limit) {
        ProactivePreferenceEntity value = new ProactivePreferenceEntity();
        value.setDeviceId(device.getId());
        value.setMacAddress(device.getMacAddress());
        value.setMode(mode.name());
        value.setDailyLimit(limit);
        value.setAllowedTopics("[]");
        value.setBlockedTopics("[]");
        value.setVersion(0);
        value.setUpdatedAt(new Date());
        return value;
    }

    private EventUpsert eventRequest() {
        EventUpsert value = new EventUpsert();
        value.setMacAddress(device.getMacAddress());
        value.setEventId("event-1");
        value.setTopic(Topic.REMINDER);
        value.setPriority(Priority.NORMAL);
        value.setReason("explicit reminder");
        value.setEventType(EventType.REMINDER);
        value.setPayload(Map.of("message", "hello"));
        value.setCreatedAt(new Date());
        value.setDedupeKey("reminder-1");
        value.setRequiresResponse(true);
        return value;
    }

    private EventClaim claim(String token) {
        EventClaim value = new EventClaim();
        value.setMacAddress(device.getMacAddress());
        value.setClaimToken(token);
        return value;
    }

    private EventUpsert rollingNewsRequest(String eventId, String message) {
        EventUpsert value = eventRequest();
        value.setEventId(eventId);
        value.setTopic(Topic.NEWS);
        value.setEventType(EventType.NEWS_ALERT);
        value.setPayload(Map.of("message", message, "source", "权威来源"));
        value.setDedupeKey("news-cluster-abcdef0123456789");
        value.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        value.setDedupeWindowHours(24);
        return value;
    }

    private EventStatusUpdate status(DeliveryStatus deliveryStatus, String token) {
        EventStatusUpdate value = new EventStatusUpdate();
        value.setMacAddress(device.getMacAddress());
        value.setDeliveryStatus(deliveryStatus);
        value.setOutcome(deliveryStatus == DeliveryStatus.FAILED ? Outcome.FAILED : Outcome.NONE);
        value.setClaimToken(token);
        return value;
    }

    private HabitObserve habitRequest(Map<String, Object> payload) {
        HabitObserve value = new HabitObserve();
        value.setMacAddress(device.getMacAddress());
        value.setHabitType(HabitType.TIME_PATTERN);
        value.setHabitKey("weekday-music");
        value.setEvidenceDelta(1);
        value.setSeenAt(new Date());
        value.setPayload(payload);
        return value;
    }

    private ProactiveEventEntity eventEntity(EventUpsert request) {
        ProactiveEventEntity value = new ProactiveEventEntity();
        value.setDeviceId(device.getId());
        value.setMacAddress(device.getMacAddress());
        value.setEventId(request.getEventId());
        value.setTopic(request.getTopic().name());
        value.setPriority(request.getPriority().name());
        value.setReason(request.getReason());
        value.setEventType(request.getEventType().name());
        value.setPayload("{\"message\":\"hello\"}");
        value.setCreatedAt(request.getCreatedAt());
        value.setDedupeKey(request.getDedupeKey());
        value.setRequiresResponse(true);
        value.setDeliveryStatus(DeliveryStatus.PENDING.name());
        value.setOutcome(Outcome.NONE.name());
        return value;
    }
}
