package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class MobileEventAuditServiceTest {
    @Test
    void verifiesInstanceOwnershipBeforeRunningPagedAuditQuery() {
        MobileInstanceDao instanceDao = mock(MobileInstanceDao.class);
        MobileEventDao eventDao = mock(MobileEventDao.class);
        MobileEventAuditService service = new MobileEventAuditService(instanceDao, eventDao);
        MobileInstanceEntity instance = new MobileInstanceEntity();
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        instance.setUserId(7L);
        when(instanceDao.selectById(instance.getMobileInstanceId())).thenReturn(instance);
        when(eventDao.pageAuditForUser(7L, instance.getMobileInstanceId(), null, null, null,
                null, null, 20, 0)).thenReturn(List.of());

        assertEquals(0, service.audit(7L, instance.getMobileInstanceId(), null, null,
                null, null, null, 1, 20).getTotal());
        verify(eventDao).countAuditForUser(7L, instance.getMobileInstanceId(), null, null,
                null, null, null);

        assertThrows(xiaozhi.common.exception.RenException.class,
                () -> service.audit(8L, instance.getMobileInstanceId(), null, null,
                        null, null, null, 1, 20));
    }
}
