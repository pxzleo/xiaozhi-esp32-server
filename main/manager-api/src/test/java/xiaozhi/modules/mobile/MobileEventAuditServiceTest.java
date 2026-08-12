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
    void auditReturnsDatabaseEpochMillisWithoutASecondTimezoneConversion() {
        MobileInstanceDao instanceDao = mock(MobileInstanceDao.class);
        MobileEventDao eventDao = mock(MobileEventDao.class);
        MobileEventAuditService service = new MobileEventAuditService(instanceDao, eventDao);
        MobileInstanceEntity instance = new MobileInstanceEntity();
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        instance.setUserId(7L);
        MobileEventAuditRow row = new MobileEventAuditRow();
        row.setOccurredAtEpochMillis(1_786_508_318_639L);
        row.setCreatedAtEpochMillis(1_786_508_319_000L);
        row.setProcessedAtEpochMillis(1_786_508_340_000L);
        when(instanceDao.selectCanonicalByInstance(instance.getMobileInstanceId())).thenReturn(instance);
        when(eventDao.pageAuditForUser(7L, instance.getMobileInstanceId(), null, null, null,
                null, null, 20, 0)).thenReturn(List.of(row));

        var view = service.audit(7L, instance.getMobileInstanceId(), null, null,
                null, null, null, 1, 20).getList().get(0);

        assertEquals(1_786_508_318_639L, view.occurredAt());
        assertEquals(1_786_508_319_000L, view.receivedAt());
        assertEquals(1_786_508_340_000L, view.processedAt());
    }

    @Test
    void verifiesInstanceOwnershipBeforeRunningPagedAuditQuery() {
        MobileInstanceDao instanceDao = mock(MobileInstanceDao.class);
        MobileEventDao eventDao = mock(MobileEventDao.class);
        MobileEventAuditService service = new MobileEventAuditService(instanceDao, eventDao);
        MobileInstanceEntity instance = new MobileInstanceEntity();
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        instance.setUserId(7L);
        when(instanceDao.selectCanonicalByInstance(instance.getMobileInstanceId())).thenReturn(instance);
        when(eventDao.pageAuditForUser(7L, instance.getMobileInstanceId(), null, null, null,
                null, null, 20, 0)).thenReturn(List.of());

        assertEquals(0, service.audit(7L, instance.getMobileInstanceId(), null, null,
                null, null, null, 1, 20).getTotal());
        verify(eventDao).countAuditForUser(7L, instance.getMobileInstanceId(), null, null,
                null, null, null);

        when(eventDao.pageAuditForUser(7L, instance.getMobileInstanceId(), null, null,
                "EXPIRED", null, null, 10, 0)).thenReturn(List.of());
        service.audit(7L, instance.getMobileInstanceId(), null, null,
                "expired", null, null, 1, 10);
        verify(eventDao).countAuditForUser(7L, instance.getMobileInstanceId(), null, null,
                "EXPIRED", null, null);

        assertThrows(xiaozhi.common.exception.RenException.class,
                () -> service.audit(8L, instance.getMobileInstanceId(), null, null,
                        null, null, null, 1, 20));
    }


    @Test
    void alertSettingsAreOwnerScopedStrictAndPersisted() {
        MobileInstanceDao instanceDao = mock(MobileInstanceDao.class);
        MobileEventDao eventDao = mock(MobileEventDao.class);
        MobileEventAuditService service = new MobileEventAuditService(instanceDao, eventDao);
        MobileInstanceEntity instance = new MobileInstanceEntity();
        instance.setMobileInstanceId("mob_0123456789abcdef0123456789abcdef");
        instance.setUserId(7L);
        when(instanceDao.selectCanonicalByInstance(instance.getMobileInstanceId())).thenReturn(instance);
        when(instanceDao.updateAlertSettings(7L, instance.getMobileInstanceId(), "timely",
                "parcel,security")).thenReturn(1);

        var defaults = service.settings(7L, instance.getMobileInstanceId());
        assertEquals("balanced", defaults.sensitivity());
        assertEquals(6, defaults.categories().size());

        var updated = service.updateSettings(7L, instance.getMobileInstanceId(),
                new MobileAlertSettingsDTOs.SettingsUpdate("timely", List.of("parcel", "security")));
        assertEquals("timely", updated.sensitivity());
        assertEquals(List.of("parcel", "security"), updated.categories());
        assertThrows(xiaozhi.common.exception.RenException.class,
                () -> service.settings(8L, instance.getMobileInstanceId()));
    }

    @Test
    void aliasRequestReadsCanonicalHistoryAndUpdatesCanonicalSettings() {
        MobileInstanceDao instanceDao = mock(MobileInstanceDao.class);
        MobileEventDao eventDao = mock(MobileEventDao.class);
        MobileEventAuditService service = new MobileEventAuditService(instanceDao, eventDao);
        String aliasId = "mob_" + "a".repeat(32);
        MobileInstanceEntity canonical = new MobileInstanceEntity();
        canonical.setMobileInstanceId("mob_" + "b".repeat(32));
        canonical.setUserId(7L);
        when(instanceDao.selectCanonicalByInstance(aliasId)).thenReturn(canonical);
        when(eventDao.pageAuditForUser(7L, canonical.getMobileInstanceId(), null, null, null,
                null, null, 20, 0)).thenReturn(List.of());
        when(instanceDao.updateAlertSettings(7L, canonical.getMobileInstanceId(), "balanced",
                "parcel")).thenReturn(1);

        service.audit(7L, aliasId, null, null, null, null, null, 1, 20);
        service.updateSettings(7L, aliasId,
                new MobileAlertSettingsDTOs.SettingsUpdate("balanced", List.of("parcel")));

        verify(eventDao).countAuditForUser(7L, canonical.getMobileInstanceId(), null, null,
                null, null, null);
        verify(instanceDao).updateAlertSettings(7L, canonical.getMobileInstanceId(), "balanced",
                "parcel");
    }
}
