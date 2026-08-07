package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;

class NeteaseSessionReservationServiceTest {
    @Test
    void locksBoundDeviceAndCommitsReservationBeforeUpstreamWork() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        DeviceNeteaseAuthDao authDao = mock(DeviceNeteaseAuthDao.class);
        DeviceNeteaseSessionDao sessionDao = mock(DeviceNeteaseSessionDao.class);
        DeviceEntity device = new DeviceEntity();
        device.setId("id");
        device.setMacAddress("AA:BB");
        device.setUserId(1L);
        when(deviceDao.selectByIdentifierForUpdate("AA:BB")).thenReturn(device);
        when(authDao.selectById("AA:BB")).thenReturn(null);
        when(sessionDao.selectOne(any())).thenReturn(null);
        NeteaseSessionReservationService service = new NeteaseSessionReservationService(
                deviceDao, authDao, sessionDao);

        var reservation = service.reserve("AA:BB");

        assertFalse(reservation.existing());
        assertNotNull(reservation.session().getSessionId());
        verify(deviceDao).selectByIdentifierForUpdate("AA:BB");
        verify(sessionDao).insert(reservation.session());
    }
}
