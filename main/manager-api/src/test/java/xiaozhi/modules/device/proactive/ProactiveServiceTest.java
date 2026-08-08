package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventStatusUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

class ProactiveServiceTest {
    private DeviceDao deviceDao;
    private ProactivePreferenceDao preferenceDao;
    private ProactiveEventDao eventDao;
    private ProactiveHabitDao habitDao;
    private ProactiveService service;
    private DeviceEntity device;

    @BeforeEach
    void setUp() {
        deviceDao = mock(DeviceDao.class);
        preferenceDao = mock(ProactivePreferenceDao.class);
        eventDao = mock(ProactiveEventDao.class);
        habitDao = mock(ProactiveHabitDao.class);
        service = new ProactiveService(deviceDao, preferenceDao, eventDao, habitDao, new ObjectMapper());
        device = new DeviceEntity();
        device.setId("device-1");
        device.setMacAddress("11:22:33:44:55:66");
        device.setUserId(7L);
        when(deviceDao.selectList(any())).thenReturn(List.of(device));
        when(deviceDao.selectById("device-1")).thenReturn(device);
    }

    @Test
    void createsAggressivePreferenceWithFiveAsDefault() {
        ProactivePreferenceEntity preference = preference(Mode.AGGRESSIVE, 5);
        when(preferenceDao.selectById("device-1")).thenReturn(preference);

        var view = service.getPreferenceByMac(device.getMacAddress());

        assertEquals(Mode.AGGRESSIVE, view.mode());
        assertEquals(5, view.dailyLimit());
        assertEquals(null, view.quietStart());
        verify(preferenceDao).insertDefault(eq("device-1"), eq(device.getMacAddress()), any());
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
        when(eventDao.selectByDeviceAndEventId("device-1", request.getEventId()))
                .thenReturn(stored);

        var view = service.upsertEvent(request);

        verify(eventDao, never()).insert(any(ProactiveEventEntity.class));
        assertEquals("hello", view.payload().get("message"));
        assertFalse(view.payload().containsKey("reasoning"));
    }

    @Test
    void rejectsUncontrolledPayloadKeys() {
        EventUpsert request = eventRequest();
        request.setPayload(Map.of("chain_of_thought", "must not persist"));
        assertThrows(RenException.class, () -> service.upsertEvent(request));
    }

    @Test
    void statusUpdateUsesBothResolvedDeviceAndEventId() {
        EventStatusUpdate update = new EventStatusUpdate();
        update.setMacAddress(device.getMacAddress());
        update.setDeliveryStatus(DeliveryStatus.DELIVERED);
        update.setOutcome(Outcome.ACKNOWLEDGED);
        when(eventDao.updateStatus(eq("device-1"), eq("event-1"), eq("DELIVERED"),
                eq("ACKNOWLEDGED"), any())).thenReturn(1);
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1"))
                .thenReturn(eventEntity(eventRequest()));

        service.updateEventStatus("event-1", update);

        verify(eventDao).updateStatus(eq("device-1"), eq("event-1"), eq("DELIVERED"),
                eq("ACKNOWLEDGED"), any());
    }

    @Test
    void rejectsSameDeviceEventIdWhenAnyAuditedFieldDiffers() {
        EventUpsert request = eventRequest();
        ProactiveEventEntity stored = eventEntity(request);
        stored.setReason("different reason");
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenReturn(stored);

        RenException error = assertThrows(RenException.class, () -> service.upsertEvent(request));

        assertEquals("event_id已存在但事件内容不一致", error.getMsg());
        verify(eventDao, never()).insert(any(ProactiveEventEntity.class));
    }

    @Test
    void sameEventIdCanBeAuditedByTwoDifferentDevices() {
        DeviceEntity other = new DeviceEntity();
        other.setId("device-2");
        other.setMacAddress("AA:BB:CC:DD:EE:FF");
        other.setUserId(8L);
        EventUpsert first = eventRequest();
        EventUpsert second = eventRequest();
        second.setMacAddress(other.getMacAddress());
        ProactiveEventEntity firstStored = eventEntity(first);
        ProactiveEventEntity secondStored = eventEntity(second);
        secondStored.setDeviceId(other.getId());
        secondStored.setMacAddress(other.getMacAddress());
        when(deviceDao.selectList(any())).thenReturn(List.of(device), List.of(other));
        when(eventDao.selectByDeviceAndEventId("device-1", "event-1")).thenReturn(null, firstStored);
        when(eventDao.selectByDeviceAndEventId("device-2", "event-1")).thenReturn(null, secondStored);

        service.upsertEvent(first);
        service.upsertEvent(second);

        verify(eventDao, times(2)).insert(any(ProactiveEventEntity.class));
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
        when(eventDao.selectByDeviceAndEventId("device-1", "fault-1")).thenReturn(null, faultStored);
        when(eventDao.selectByDeviceAndEventId("device-1", "recovery-1")).thenReturn(null, recoveryStored);

        service.upsertEvent(fault);
        service.upsertEvent(recovery);

        verify(eventDao, times(2)).insert(any(ProactiveEventEntity.class));
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
                () -> service.events(7L, null, null, null, null, 100_001, 100));
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
