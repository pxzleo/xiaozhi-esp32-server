package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class ProactiveScheduleAuthorityTest {
    private final DeviceDao devices=mock(DeviceDao.class);
    private final ProactiveScheduleDao schedules=mock(ProactiveScheduleDao.class);
    private final ProactiveService proactive=mock(ProactiveService.class);
    private final ProactiveEventDao events=mock(ProactiveEventDao.class);
    private final ProactiveScheduleRevisionDao revisions=mock(ProactiveScheduleRevisionDao.class);
    private final ProactiveScheduleActionDao actions=mock(ProactiveScheduleActionDao.class);
    private final ProactiveDeliveryRoutingService routing=mock(ProactiveDeliveryRoutingService.class);
    private final ProactiveScheduleService service=new ProactiveScheduleService(
            devices,schedules,proactive,events,revisions,actions,routing);

    @Test
    void syncUsesRequesterTenantAndPaginatesFromZero() {
        DeviceEntity requester=device("requester",7L,"AA");
        DeviceEntity source=device("source",7L,"BB");
        when(devices.selectList(any())).thenReturn(List.of(requester));
        when(devices.selectById("source")).thenReturn(source);
        ProactiveScheduleEntity first=schedule(7L,"source",3L);
        ProactiveScheduleEntity second=schedule(7L,"source",5L);
        when(schedules.selectChanges(7L,0,2)).thenReturn(List.of(first,second));

        var response=service.sync("AA",0,1);

        assertTrue(response.fullSnapshot());
        assertTrue(response.hasMore());
        assertEquals(3L,response.cursor());
        assertEquals("BB",response.changes().getFirst().sourceMacAddress());
        assertFalse(response.changes().getFirst().localSource());
        verify(schedules).selectChanges(7L,0,2);
        verify(schedules,never()).selectChanges(eq(8L),anyLong(),anyInt());
    }

    @Test
    void deletedScheduleIsExplicitTombstone() {
        DeviceEntity requester=device("source",7L,"AA");
        when(devices.selectList(any())).thenReturn(List.of(requester));
        when(devices.selectById("source")).thenReturn(requester);
        ProactiveScheduleEntity deleted=schedule(7L,"source",9L);
        deleted.setDeletedAt(new Date(2_000)); deleted.setState("deleted");
        when(schedules.selectChanges(7L,8,2)).thenReturn(List.of(deleted));

        var change=service.sync("AA",8,1).changes().getFirst();

        assertEquals("delete",change.operation());
        assertEquals("11111111-1111-4111-8111-111111111111",change.scheduleId());
        assertTrue(change.localSource());
        assertNull(change.kind());
        verify(schedules,never()).selectLatestEvent(any(),any());
    }

    @Test
    void syncExplicitlyRejectsLegacyLabelBeyondDeviceBudget() {
        DeviceEntity requester=device("source",7L,"AA");
        when(devices.selectList(any())).thenReturn(List.of(requester));
        ProactiveScheduleEntity legacy=schedule(7L,"source",9L);
        legacy.setLabel("字".repeat(81));
        when(schedules.selectChanges(7L,0,2)).thenReturn(List.of(legacy));

        assertThrows(RenException.class,()->service.sync("AA",0,1));
    }

    @Test
    void actionReadAndAckAreTenantScopedAndMonotonic() {
        DeviceEntity requester=device("requester",7L,"AA");
        when(devices.selectList(any())).thenReturn(List.of(requester));
        when(actions.acknowledged("requester")).thenReturn(4L,8L);
        when(actions.selectAfter(7L,4,2)).thenReturn(List.of());
        assertEquals(4L,service.actions("AA",2,1).cursor());
        verify(actions).selectAfter(7L,4,2);

        when(actions.highWater(7L)).thenReturn(8L);
        ProactiveScheduleDTOs.AckRequest ack=new ProactiveScheduleDTOs.AckRequest();
        ack.setProtocolVersion(1); ack.setThroughRevision(8L);
        assertEquals(8L,service.acknowledge("AA",ack).ackedRevision());
        verify(actions).acknowledge("requester",8L);

        ack.setThroughRevision(9L);
        assertThrows(RenException.class,()->service.acknowledge("AA",ack));
        verify(actions,never()).acknowledge("requester",9L);
    }

    @Test
    void uuidActionRejectsRequesterFromAnotherTenant() {
        DeviceEntity requester=device("other",8L,"CC");
        when(devices.selectList(any())).thenReturn(List.of(requester));
        ProactiveScheduleEntity entity=schedule(7L,"source",3L);
        when(schedules.selectById(entity.getId())).thenReturn(entity);
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        ProactiveScheduleDTOs.Action request=new ProactiveScheduleDTOs.Action();
        request.setAction("delete"); request.setRequesterMacAddress("CC");

        assertThrows(RenException.class,()->service.action(entity.getId(),request));
        verify(schedules,never()).updateById(any(ProactiveScheduleEntity.class));
        verify(revisions,never()).insert(any(ProactiveScheduleRevisionEntity.class));
    }

    @Test
    void uuidTriggerRejectsRequesterFromAnotherTenant() {
        DeviceEntity requester=device("other",8L,"CC");
        when(devices.selectList(any())).thenReturn(List.of(requester));
        ProactiveScheduleEntity entity=schedule(7L,"source",3L);
        when(schedules.selectById(entity.getId())).thenReturn(entity);
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        ProactiveScheduleDTOs.Trigger request=new ProactiveScheduleDTOs.Trigger();
        request.setTriggeredAt(1_000L); request.setRequesterMacAddress("CC");

        assertThrows(RenException.class,()->service.trigger(entity.getId(),request));
        verify(schedules,never()).updateById(any(ProactiveScheduleEntity.class));
        verify(proactive,never()).upsertEvent(any());
        verify(revisions,never()).insert(any(ProactiveScheduleRevisionEntity.class));
    }

    @Test
    void deletePublishesTombstoneAndActionAfterUserFirstLock() {
        ProactiveScheduleEntity entity=schedule(7L,"source",3L);
        when(schedules.selectById(entity.getId())).thenReturn(entity);
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        when(revisions.insert(any(ProactiveScheduleRevisionEntity.class))).thenAnswer(call->{
            ((ProactiveScheduleRevisionEntity)call.getArgument(0)).setRevision(10L); return 1;
        });
        when(actions.insert(any(ProactiveScheduleActionEntity.class))).thenReturn(1);
        ProactiveScheduleDTOs.Action request=new ProactiveScheduleDTOs.Action();
        request.setAction("delete");

        var result=service.action(entity.getId(),request);

        assertEquals("deleted",result.state());
        assertNull(result.nextTriggerAt());
        assertEquals(10L,entity.getSyncRevision());
        InOrder order=inOrder(routing,schedules);
        order.verify(routing).lockUser(7L);
        order.verify(schedules).selectForUpdate(entity.getId());
        verify(events).dismissActiveScheduleEvents(7L,entity.getId());
        verify(actions).insert(argThat((ProactiveScheduleActionEntity row)->"delete".equals(row.getAction())
                && row.getScheduleVersion()==2));
    }

    @Test
    void dueFlowLocksUserBeforeDueRowAndSecondWorkerFindsNothing() {
        ProactiveScheduleEntity entity=schedule(7L,"source",3L);
        entity.setNextTriggerAt(new Date(1_000));
        when(schedules.selectDueCandidate()).thenReturn(entity,entity);
        when(schedules.selectDueForUpdate(entity.getId())).thenReturn(entity,null);
        when(schedules.selectDatabaseNow()).thenReturn(new Date(1_500));
        when(schedules.updateById(entity)).thenReturn(1);
        when(revisions.insert(any(ProactiveScheduleRevisionEntity.class))).thenAnswer(call->{
            ((ProactiveScheduleRevisionEntity)call.getArgument(0)).setRevision(4L); return 1;
        });
        when(devices.selectById("source")).thenReturn(device("source",7L,"BB"));

        assertTrue(service.processOneDue());
        assertFalse(service.processOneDue());
        verify(proactive,times(1)).upsertEvent(any());
        InOrder order=inOrder(routing,schedules);
        order.verify(routing).lockUser(7L);
        order.verify(schedules).selectDueForUpdate(entity.getId());
    }

    @Test
    void onceStopAndCompleteClearNextTriggerWhileSnoozeUsesOverride() {
        assertOnceActionNextTrigger("stop",null,null);
        assertOnceActionNextTrigger("complete",null,null);
        assertOnceActionNextTrigger("snooze",2_000L,new Date(2_000));
    }

    @Test
    void recurringActionsPreservePeriodicNextTrigger() {
        for (String action : List.of("stop","complete","snooze")) {
            ProactiveScheduleEntity entity=schedule(7L,"source",3L);
            entity.setRecurrence("daily");
            Date periodicNext=new Date(86_401_000L);
            entity.setNextTriggerAt(periodicNext);
            when(schedules.selectById(entity.getId())).thenReturn(entity);
            when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
            when(schedules.updateById(entity)).thenReturn(1);
            when(revisions.insert(any(ProactiveScheduleRevisionEntity.class))).thenAnswer(call->{
                ((ProactiveScheduleRevisionEntity)call.getArgument(0)).setRevision(10L); return 1;
            });
            when(actions.insert(any(ProactiveScheduleActionEntity.class))).thenReturn(1);
            ProactiveScheduleDTOs.Action request=new ProactiveScheduleDTOs.Action();
            request.setAction(action);
            if ("snooze".equals(action)) request.setSnoozedUntil(2_000L);

            var result=service.action(entity.getId(),request);

            assertEquals(periodicNext.getTime(),result.nextTriggerAt(),action);
            assertEquals("snooze".equals(action)?2_000L:null,result.snoozedUntil(),action);
            reset(schedules,revisions,actions,routing,events);
        }
    }

    @Test
    void activeScheduleOrderingUsesEffectiveNextTrigger() throws Exception {
        String sql=ProactiveScheduleDao.class.getMethod("selectActiveForUser",Long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(sql.contains("ORDER BY COALESCE(snoozed_until,next_trigger_at),id"));
    }

    @Test
    void deletedSourceScheduleRejectsEveryNonDeleteAction() {
        for (String action : List.of("stop","snooze","complete")) {
            DeviceEntity source=device("source",7L,"AA");
            ProactiveScheduleEntity deleted=schedule(7L,"source",3L);
            deleted.setState("deleted"); deleted.setDeletedAt(new Date(2_000));
            when(devices.selectList(any())).thenReturn(List.of(source));
            when(schedules.selectSourceForUpdate("source","1")).thenReturn(deleted);
            ProactiveScheduleDTOs.SourceAction request=new ProactiveScheduleDTOs.SourceAction();
            request.setSourceMacAddress("AA"); request.setSourceScheduleId("1");
            request.setAction(action);
            if ("snooze".equals(action)) request.setSnoozedUntil(3_000L);

            assertThrows(RenException.class,()->service.actionBySource(request),action);
            verify(schedules,never()).updateById(any(ProactiveScheduleEntity.class));
            verify(revisions,never()).insert(any(ProactiveScheduleRevisionEntity.class));
            verify(actions,never()).insert(any(ProactiveScheduleActionEntity.class));
            reset(devices,schedules,revisions,actions,routing,events);
        }
    }

    private void assertOnceActionNextTrigger(String action,Long snoozedUntil,Date expected) {
        ProactiveScheduleEntity entity=schedule(7L,"source",3L);
        when(schedules.selectById(entity.getId())).thenReturn(entity);
        when(schedules.selectForUpdate(entity.getId())).thenReturn(entity);
        when(schedules.updateById(entity)).thenReturn(1);
        when(revisions.insert(any(ProactiveScheduleRevisionEntity.class))).thenAnswer(call->{
            ((ProactiveScheduleRevisionEntity)call.getArgument(0)).setRevision(10L); return 1;
        });
        when(actions.insert(any(ProactiveScheduleActionEntity.class))).thenReturn(1);
        ProactiveScheduleDTOs.Action request=new ProactiveScheduleDTOs.Action();
        request.setAction(action); request.setSnoozedUntil(snoozedUntil);

        var result=service.action(entity.getId(),request);

        assertEquals(expected==null?null:expected.getTime(),result.nextTriggerAt(),action);
        reset(schedules,revisions,actions,routing,events);
    }

    private DeviceEntity device(String id,Long userId,String mac) {
        DeviceEntity value=new DeviceEntity(); value.setId(id); value.setUserId(userId);
        value.setMacAddress(mac); return value;
    }

    private ProactiveScheduleEntity schedule(Long userId,String source,long revision) {
        ProactiveScheduleEntity value=new ProactiveScheduleEntity();
        value.setId("11111111-1111-4111-8111-111111111111"); value.setUserId(userId);
        value.setSourceDeviceId(source); value.setSourceScheduleId("1");
        value.setKind("reminder"); value.setLabel("喝水"); value.setRecurrence("once");
        value.setWeekdays("[]"); value.setSections("[]"); value.setScheduledAt(new Date(1_000));
        value.setNextTriggerAt(new Date(1_000)); value.setState("scheduled"); value.setVersion(1);
        value.setSyncRevision(revision); value.setCreatedAt(new Date(500));
        value.setUpdatedAt(new Date(500)); return value;
    }
}
