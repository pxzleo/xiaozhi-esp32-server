package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class ProactiveScheduleServiceTest {
    @Test
    void dueScheduleUsesDatabaseClockAndAtomicDueRow() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("scheduled");
        entity.setNextTriggerAt(new Date(1_000));
        when(schedules.selectDueCandidate()).thenReturn(entity);
        when(schedules.selectDueForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.selectDatabaseNow()).thenReturn(new Date(1_500));
        when(schedules.updateById(entity)).thenReturn(1);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L);
        when(devices.selectById("d1")).thenReturn(source);

        assertEquals(true, new ProactiveScheduleService(devices,schedules,proactive,events)
                .processOneDue());
        verify(proactive).upsertEvent(argThat(event -> event.getEventId().endsWith("-1000")));
    }

    @Test
    void triggerUsesStableUuidAndEpochInDerivedEventId() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("scheduled");
        when(schedules.selectForUpdate("11111111-1111-1111-1111-111111111111")).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L);
        when(devices.selectById("d1")).thenReturn(source);

        ProactiveScheduleDTOs.Trigger request=new ProactiveScheduleDTOs.Trigger();
        request.setTriggeredAt(1786578000123L);
        var result=new ProactiveScheduleService(devices,schedules,proactive,events)
                .trigger(entity.getId(),request);

        assertEquals(entity.getId(),result.id());
        verify(proactive).upsertEvent(argThat(event -> event.getEventId().equals(
                "schedule-11111111-1111-1111-1111-111111111111-1786578000123")
                && !event.getRequiresResponse()));
    }

    @Test
    void repeatedTriggerAfterCommittedTriggerIsIdempotent() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("triggered");
        entity.setLastTriggeredAt(new Date(1786578000123L));
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        ProactiveScheduleDTOs.Trigger request=new ProactiveScheduleDTOs.Trigger();
        request.setTriggeredAt(1786578000123L);

        var result=new ProactiveScheduleService(devices,schedules,proactive,events)
                .trigger(entity.getId(),request);

        assertEquals(entity.getId(),result.id());
        verify(proactive,never()).upsertEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void existingScheduleRejectsNonAuthoritativeOccurrence() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("scheduled");
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        ProactiveScheduleDTOs.Trigger request=new ProactiveScheduleDTOs.Trigger();
        request.setTriggeredAt(entity.getNextTriggerAt().getTime()+1);

        assertThrows(xiaozhi.common.exception.RenException.class,()->
                new ProactiveScheduleService(devices,schedules,proactive,events)
                        .trigger(entity.getId(),request));

        verify(schedules,never()).updateById(entity);
        verify(proactive,never()).upsertEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void snoozedScheduleOnlyTriggersTemporaryAuthoritativeOccurrence() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("snoozed");
        entity.setRecurrence("daily");
        entity.setNextTriggerAt(new Date(1786664400123L));
        entity.setSnoozedUntil(new Date(1786578300123L));
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L); when(devices.selectById("d1")).thenReturn(source);
        ProactiveScheduleService service=new ProactiveScheduleService(devices,schedules,proactive,events);
        ProactiveScheduleDTOs.Trigger wrong=new ProactiveScheduleDTOs.Trigger();
        wrong.setTriggeredAt(entity.getNextTriggerAt().getTime());
        ProactiveScheduleDTOs.Trigger exact=new ProactiveScheduleDTOs.Trigger();
        exact.setTriggeredAt(entity.getSnoozedUntil().getTime());

        assertThrows(xiaozhi.common.exception.RenException.class,()->service.trigger(entity.getId(),wrong));
        var result=service.trigger(entity.getId(),exact);

        assertEquals(1786664400123L,result.nextTriggerAt());
        assertEquals(null,result.snoozedUntil());
        verify(proactive,times(1)).upsertEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recurringScheduleCanTriggerTwoDifferentCyclesWhilePreviousStateIsTriggered() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("scheduled");
        entity.setRecurrence("daily");
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L);
        when(devices.selectById("d1")).thenReturn(source);
        ProactiveScheduleService service=new ProactiveScheduleService(devices,schedules,proactive,events);

        ProactiveScheduleDTOs.Trigger first=new ProactiveScheduleDTOs.Trigger();
        first.setTriggeredAt(1786578000123L);
        ProactiveScheduleDTOs.Trigger second=new ProactiveScheduleDTOs.Trigger();
        second.setTriggeredAt(1786664400123L);
        service.trigger(entity.getId(),first);
        service.trigger(entity.getId(),second);

        verify(proactive).upsertEvent(argThat(event -> event.getEventId().endsWith("-1786578000123")
                && "2026-08-13T07:40:00.123".equals(event.getPayload().get("scheduled_at"))));
        verify(proactive).upsertEvent(argThat(event -> event.getEventId().endsWith("-1786664400123")
                && "2026-08-14T07:40:00.123".equals(event.getPayload().get("scheduled_at"))));
        verify(proactive,times(2)).upsertEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void briefingTriggerCarriesAuthoritativeSectionsAndLocation() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        ProactiveScheduleEntity entity=schedule("scheduled");
        entity.setKind("briefing"); entity.setLabel("每日简报");
        entity.setSections("[\"weather\",\"news\"]"); entity.setLocation("广州");
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L);
        when(devices.selectById("d1")).thenReturn(source);
        ProactiveScheduleDTOs.Trigger request=new ProactiveScheduleDTOs.Trigger();
        request.setTriggeredAt(1786578000123L);

        var result=new ProactiveScheduleService(devices,schedules,proactive,events)
                .trigger(entity.getId(),request);

        assertEquals("schedule-11111111-1111-1111-1111-111111111111-1786578000123",
                result.eventId());
        verify(proactive).upsertEvent(argThat(event -> event.getEventType()==ProactiveEnums.EventType.REMINDER
                && event.getTopic()==ProactiveEnums.Topic.NEWS
                && "weather,news".equals(event.getPayload().get("action"))
                && "广州".equals(event.getPayload().get("source"))));
    }

    @Test
    void sourceBriefingWithoutLabelUsesFixedLabelAndCompensatesMissingRegistration() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L);
        when(devices.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(source));
        when(devices.selectById("d1")).thenReturn(source);
        AtomicReference<ProactiveScheduleEntity> inserted=new AtomicReference<>();
        when(schedules.insert(org.mockito.ArgumentMatchers.any(ProactiveScheduleEntity.class))).thenAnswer(call -> {
            inserted.set(call.getArgument(0)); return 1;
        });
        when(schedules.selectSourceForUpdate("d1","7")).thenAnswer(call -> inserted.get());
        when(schedules.updateById(org.mockito.ArgumentMatchers.any(ProactiveScheduleEntity.class))).thenReturn(1);
        ProactiveScheduleDTOs.SourceTrigger request=new ProactiveScheduleDTOs.SourceTrigger();
        request.setSourceMacAddress("AA"); request.setSourceScheduleId("7");
        request.setKind("briefing"); request.setSections(List.of("weather","news"));
        request.setLocation("广州"); request.setTriggeredAt(1786578000123L);

        var result=new ProactiveScheduleService(devices,schedules,proactive,events)
                .triggerBySource(request);

        assertEquals("每日简报",inserted.get().getLabel());
        assertEquals("[\"weather\",\"news\"]",inserted.get().getSections());
        assertEquals("广州",inserted.get().getLocation());
        assertEquals("schedule-"+inserted.get().getId()+"-1786578000123",result.eventId());
    }

    @Test
    void sourceStopUsesLockedServerVersionAndRepeatedCallIsIdempotent() {
        DeviceDao devices=mock(DeviceDao.class);
        ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
        ProactiveService proactive=mock(ProactiveService.class);
        ProactiveEventDao events=mock(ProactiveEventDao.class);
        DeviceEntity source=new DeviceEntity(); source.setId("d1"); source.setMacAddress("AA");
        source.setUserId(7L);
        when(devices.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(source));
        ProactiveScheduleEntity entity=schedule("triggered"); entity.setVersion(4);
        when(schedules.selectSourceForUpdate("d1","7")).thenReturn(entity);
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        ProactiveScheduleDTOs.SourceAction request=new ProactiveScheduleDTOs.SourceAction();
        request.setSourceMacAddress("AA"); request.setSourceScheduleId("7"); request.setAction("stop");
        ProactiveScheduleService service=new ProactiveScheduleService(devices,schedules,proactive,events);

        assertEquals("stopped",service.actionBySource(request).state());
        assertEquals("stopped",service.actionBySource(request).state());

        verify(schedules,times(1)).updateById(entity);
        verify(events,times(2)).dismissActiveScheduleEvents(7L,entity.getId());
    }

    private ProactiveScheduleEntity schedule(String state) {
        ProactiveScheduleEntity entity=new ProactiveScheduleEntity();
        entity.setId("11111111-1111-1111-1111-111111111111"); entity.setUserId(7L);
        entity.setSourceDeviceId("d1"); entity.setSourceScheduleId("12");
        entity.setKind("alarm"); entity.setLabel("起床"); entity.setRecurrence("once");
        entity.setWeekdays("[]"); entity.setSections("[]");
        entity.setScheduledAt(new Date(1786578000123L));
        entity.setNextTriggerAt(new Date(1786578000123L));
        entity.setState(state); entity.setVersion(1); return entity;
    }
}
