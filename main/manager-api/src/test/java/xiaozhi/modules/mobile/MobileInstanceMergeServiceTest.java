package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MergeRequest;

class MobileInstanceMergeServiceTest {
    @Test
    void mergesOnlyOwnedSameAgentCanonicalGroupsWithoutDeletingHistory() {
        MobileInstanceDao dao = mock(MobileInstanceDao.class);
        MobileInstanceMergeService service = new MobileInstanceMergeService(dao);
        MobileInstanceEntity canonical = instance("mob_" + "a".repeat(32), "a".repeat(32), "agent-1");
        MobileInstanceEntity duplicate = instance("mob_" + "b".repeat(32), "b".repeat(32), "agent-1");
        when(dao.selectByDeviceForUpdate(7L, canonical.getDeviceId())).thenReturn(canonical);
        when(dao.selectByDeviceForUpdate(7L, duplicate.getDeviceId())).thenReturn(duplicate);
        when(dao.selectCanonicalByInstanceForUpdate(canonical.getMobileInstanceId())).thenReturn(canonical);
        when(dao.selectCanonicalGroupForUpdate(7L, canonical.getCanonicalInstanceId()))
                .thenReturn(List.of(canonical));
        when(dao.selectCanonicalGroupForUpdate(7L, duplicate.getCanonicalInstanceId()))
                .thenReturn(List.of(duplicate));

        service.merge(7L, new MergeRequest(canonical.getDeviceId(), List.of(duplicate.getDeviceId())));

        verify(dao).mergeCanonicalGroup(7L, duplicate.getCanonicalInstanceId(),
                canonical.getCanonicalInstanceId());
        verify(dao, never()).retireMergedAliases(7L, canonical.getMobileInstanceId());
    }

    @Test
    void rejectsCrossAgentMerge() {
        MobileInstanceDao dao = mock(MobileInstanceDao.class);
        MobileInstanceMergeService service = new MobileInstanceMergeService(dao);
        MobileInstanceEntity canonical = instance("mob_" + "a".repeat(32), "a".repeat(32), "agent-1");
        MobileInstanceEntity duplicate = instance("mob_" + "b".repeat(32), "b".repeat(32), "agent-2");
        when(dao.selectByDeviceForUpdate(7L, canonical.getDeviceId())).thenReturn(canonical);
        when(dao.selectByDeviceForUpdate(7L, duplicate.getDeviceId())).thenReturn(duplicate);
        when(dao.selectCanonicalByInstanceForUpdate(canonical.getMobileInstanceId())).thenReturn(canonical);

        assertThrows(RenException.class, () -> service.merge(7L,
                new MergeRequest(canonical.getDeviceId(), List.of(duplicate.getDeviceId()))));
    }

    @Test
    void rejectsDifferentStablePhonesAndKeepsBothGroupsUntouched() {
        MobileInstanceDao dao = mock(MobileInstanceDao.class);
        MobileInstanceMergeService service = new MobileInstanceMergeService(dao);
        MobileInstanceEntity canonical = instance("mob_" + "a".repeat(32), "a".repeat(32), "agent-1");
        canonical.setStableDeviceKey("1".repeat(64));
        MobileInstanceEntity duplicate = instance("mob_" + "b".repeat(32), "b".repeat(32), "agent-1");
        duplicate.setStableDeviceKey("2".repeat(64));
        when(dao.selectByDeviceForUpdate(7L, canonical.getDeviceId())).thenReturn(canonical);
        when(dao.selectByDeviceForUpdate(7L, duplicate.getDeviceId())).thenReturn(duplicate);
        when(dao.selectCanonicalByInstanceForUpdate(canonical.getMobileInstanceId())).thenReturn(canonical);
        when(dao.selectCanonicalGroupForUpdate(7L, canonical.getCanonicalInstanceId()))
                .thenReturn(List.of(canonical));
        when(dao.selectCanonicalGroupForUpdate(7L, duplicate.getCanonicalInstanceId()))
                .thenReturn(List.of(duplicate));

        assertThrows(RenException.class, () -> service.merge(7L,
                new MergeRequest(canonical.getDeviceId(), List.of(duplicate.getDeviceId()))));

        verify(dao, never()).mergeCanonicalGroup(7L, duplicate.getCanonicalInstanceId(),
                canonical.getCanonicalInstanceId());
    }

    @Test
    void transfersSingleStableKeyToCanonicalWithoutDisconnectingCurrentSession() {
        MobileInstanceDao dao = mock(MobileInstanceDao.class);
        MobileInstanceMergeService service = new MobileInstanceMergeService(dao);
        MobileInstanceEntity canonical = instance("mob_" + "a".repeat(32), "a".repeat(32), "agent-1");
        MobileInstanceEntity duplicate = instance("mob_" + "b".repeat(32), "b".repeat(32), "agent-1");
        duplicate.setStableDeviceKey("3".repeat(64));
        when(dao.selectByDeviceForUpdate(7L, canonical.getDeviceId())).thenReturn(canonical);
        when(dao.selectByDeviceForUpdate(7L, duplicate.getDeviceId())).thenReturn(duplicate);
        when(dao.selectCanonicalByInstanceForUpdate(canonical.getMobileInstanceId())).thenReturn(canonical);
        when(dao.selectCanonicalGroupForUpdate(7L, canonical.getCanonicalInstanceId()))
                .thenReturn(List.of(canonical));
        when(dao.selectCanonicalGroupForUpdate(7L, duplicate.getCanonicalInstanceId()))
                .thenReturn(List.of(duplicate));
        when(dao.setStableDeviceKey(7L, canonical.getMobileInstanceId(), "3".repeat(64)))
                .thenReturn(1);

        service.merge(7L, new MergeRequest(canonical.getDeviceId(), List.of(duplicate.getDeviceId())));

        verify(dao).setStableDeviceKey(7L, canonical.getMobileInstanceId(), "3".repeat(64));
        verify(dao, never()).retireMergedAliases(7L, canonical.getMobileInstanceId());
    }

    private MobileInstanceEntity instance(String id, String deviceId, String agentId) {
        MobileInstanceEntity entity = new MobileInstanceEntity();
        entity.setMobileInstanceId(id);
        entity.setCanonicalInstanceId(id);
        entity.setDeviceId(deviceId);
        entity.setAgentId(agentId);
        entity.setUserId(7L);
        return entity;
    }
}
