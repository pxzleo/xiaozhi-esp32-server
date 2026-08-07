package xiaozhi.modules.device.service.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.device.netease.DeviceNeteaseService;
import xiaozhi.modules.device.service.DeviceAddressBookService;

class DeviceNeteaseCleanupTest {
    @Test
    void unbindOnlyRevokesDeviceActuallyOwnedAndDeleted() {
        Fixture fixture = new Fixture();
        DeviceEntity device = device("id", "AA:BB", 7L);
        when(fixture.dao.selectByIdForUpdate("id")).thenReturn(device);

        fixture.service.unbindDevice(8L, "id");
        verify(fixture.netease, never()).revokeForDeviceRemoval(any());

        fixture.service.unbindDevice(7L, "id");
        verify(fixture.dao, org.mockito.Mockito.times(2)).selectByIdForUpdate("id");
        verify(fixture.netease).revokeForDeviceRemoval("AA:BB");
        verify(fixture.dao).delete(any());
    }

    @Test
    void userAndAgentDeletionRevokeEachSelectedDeviceBeforeDelete() {
        Fixture fixture = new Fixture();
        List<DeviceEntity> devices = List.of(
                device("one", "AA:01", 7L), device("two", "AA:02", 7L));
        when(fixture.dao.selectByUserIdForUpdate(7L)).thenReturn(devices);
        when(fixture.dao.selectByAgentIdForUpdate("agent")).thenReturn(devices);

        fixture.service.deleteByUserId(7L);
        fixture.service.deleteByAgentId("agent");

        verify(fixture.dao).selectByUserIdForUpdate(7L);
        verify(fixture.dao).selectByAgentIdForUpdate("agent");
        verify(fixture.netease, org.mockito.Mockito.times(2)).revokeForDeviceRemoval("AA:01");
        verify(fixture.netease, org.mockito.Mockito.times(2)).revokeForDeviceRemoval("AA:02");
    }

    private static DeviceEntity device(String id, String mac, Long userId) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setMacAddress(mac);
        device.setUserId(userId);
        return device;
    }

    private static class Fixture {
        final DeviceDao dao = mock(DeviceDao.class);
        final DeviceNeteaseService netease = mock(DeviceNeteaseService.class);
        final DeviceAddressBookService addressBook = mock(DeviceAddressBookService.class);
        final DeviceServiceImpl service = new DeviceServiceImpl(
                dao, null, null, null, null, addressBook, netease);

        Fixture() {
            ReflectionTestUtils.setField(service, "baseDao", dao);
        }
    }
}
