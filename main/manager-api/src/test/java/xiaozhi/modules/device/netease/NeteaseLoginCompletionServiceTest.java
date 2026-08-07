package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.mockito.ArgumentCaptor;

import org.junit.jupiter.api.Test;

class NeteaseLoginCompletionServiceTest {
    @Test
    void rejectedDatabaseClaimDoesNotWriteCredential() {
        DeviceNeteaseSessionDao sessionDao = mock(DeviceNeteaseSessionDao.class);
        DeviceNeteaseAuthDao authDao = mock(DeviceNeteaseAuthDao.class);
        NeteaseCredentialCipher cipher = mock(NeteaseCredentialCipher.class);
        when(sessionDao.completeIfActive(any(), any(), any())).thenReturn(0);
        NeteaseLoginCompletionService service = new NeteaseLoginCompletionService(sessionDao, authDao, cipher);

        assertFalse(service.complete("session", "device", "cookie", "user", "nick", "avatar"));
        verify(authDao, never()).insert(any(DeviceNeteaseAuthEntity.class));
        verify(cipher, never()).encrypt(any());
    }

    @Test
    void successfulClaimWritesCredential() {
        DeviceNeteaseSessionDao sessionDao = mock(DeviceNeteaseSessionDao.class);
        DeviceNeteaseAuthDao authDao = mock(DeviceNeteaseAuthDao.class);
        NeteaseCredentialCipher cipher = mock(NeteaseCredentialCipher.class);
        when(sessionDao.completeIfActive(any(), any(), any())).thenReturn(1);
        when(authDao.selectById("device")).thenReturn(null);
        when(cipher.encrypt("cookie")).thenReturn("encrypted");
        NeteaseLoginCompletionService service = new NeteaseLoginCompletionService(sessionDao, authDao, cipher);

        assertTrue(service.complete("session", "device", "cookie", "user", "nick", "avatar"));
        verify(authDao).insert(any(DeviceNeteaseAuthEntity.class));
    }

    @Test
    void reloginIncrementsCredentialVersion() {
        DeviceNeteaseSessionDao sessionDao = mock(DeviceNeteaseSessionDao.class);
        DeviceNeteaseAuthDao authDao = mock(DeviceNeteaseAuthDao.class);
        NeteaseCredentialCipher cipher = mock(NeteaseCredentialCipher.class);
        DeviceNeteaseAuthEntity existing = new DeviceNeteaseAuthEntity();
        existing.setDeviceId("device");
        existing.setVersion(4);
        when(sessionDao.completeIfActive(any(), any(), any())).thenReturn(1);
        when(authDao.selectById("device")).thenReturn(existing);
        when(cipher.encrypt("cookie")).thenReturn("encrypted");
        NeteaseLoginCompletionService service = new NeteaseLoginCompletionService(sessionDao, authDao, cipher);

        assertTrue(service.complete("session", "device", "cookie", "user", "nick", "avatar"));
        ArgumentCaptor<DeviceNeteaseAuthEntity> captor = ArgumentCaptor.forClass(DeviceNeteaseAuthEntity.class);
        verify(authDao).updateById(captor.capture());
        assertEquals(5, captor.getValue().getVersion());
    }
}
