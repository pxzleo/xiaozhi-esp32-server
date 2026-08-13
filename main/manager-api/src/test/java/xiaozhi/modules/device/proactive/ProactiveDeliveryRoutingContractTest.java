package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import com.fasterxml.jackson.databind.ObjectMapper;

import xiaozhi.modules.device.dao.DeviceDao;
import xiaozhi.modules.device.entity.DeviceEntity;
import xiaozhi.modules.mobile.MobileInstanceDao;
import xiaozhi.modules.mobile.MobileInstanceEntity;
import xiaozhi.modules.sys.dao.SysUserDao;

class ProactiveDeliveryRoutingContractTest {
    @Test
    void absentRoutingDefaultsToEveryOwnedDevice() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        ProactiveDeliveryRoutingDao routingDao = mock(ProactiveDeliveryRoutingDao.class);
        MobileInstanceDao mobileDao = mock(MobileInstanceDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        when(userDao.selectIdForUpdate(7L)).thenReturn(7L);
        DeviceEntity first = device("d1", 7L);
        DeviceEntity second = device("d2", 7L);
        when(deviceDao.selectRoutableByUserForUpdate(7L)).thenReturn(List.of(first, second));

        ProactiveDeliveryRoutingService service = new ProactiveDeliveryRoutingService(
                deviceDao, routingDao, mobileDao, userDao);
        assertEquals(List.of("d1", "d2"), service.resolveTargetDeviceIds(7L));
    }

    @Test
    void freshAuthoritativeLocationUsesPlaceRouteButExpiredLocationUsesDefault() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        ProactiveDeliveryRoutingDao routingDao = mock(ProactiveDeliveryRoutingDao.class);
        MobileInstanceDao mobileDao = mock(MobileInstanceDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        when(userDao.selectIdForUpdate(7L)).thenReturn(7L);
        when(deviceDao.selectRoutableByUserForUpdate(7L)).thenReturn(List.of(
                device("d1", 7L), device("d2", 7L)));
        when(routingDao.selectAccountForUpdate(7L)).thenReturn(
                new ProactiveDeliveryRoutingDao.AccountRow(7L, "[\"d1\"]", "mob_a", 3));
        when(routingDao.selectActivePlaceDeviceIds(eq(7L), eq("mob_a"), any()))
                .thenReturn(List.of("d2"));
        when(routingDao.selectFreshLocation(7L, "mob_a")).thenReturn(new ProactiveDeliveryRoutingDao.LocationRow(
                7L, "mob_a", "place_12345678", "entered", new java.util.Date()));
        when(routingDao.selectPlaces(7L)).thenReturn(List.of(new ProactiveDeliveryRoutingDao.PlaceRow(
                7L, "place_12345678", "家", "[\"d2\"]")));

        ProactiveDeliveryRoutingService service = new ProactiveDeliveryRoutingService(
                deviceDao, routingDao, mobileDao, userDao);
        assertEquals(List.of("d2"), service.resolveTargetDeviceIds(7L));

        when(routingDao.selectFreshLocation(7L, "mob_a")).thenReturn(null);
        assertEquals(List.of("d1"), service.resolveTargetDeviceIds(7L));
    }

    @Test
    void migrationAddsNewOnlyMulticastMarkerAndRoutingTablesLast() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/changelog/202608131200.sql"));
        String master = Files.readString(Path.of(
                "src/main/resources/db/changelog/db.changelog-master.yaml"));

        assertTrue(sql.contains("DEFAULT 'LEGACY_COMPETE'"));
        assertTrue(sql.contains("ai_proactive_delivery_route_account"));
        assertTrue(sql.contains("ai_proactive_delivery_route_place"));
        assertTrue(sql.contains("ai_proactive_device_route"));
        assertTrue(sql.contains("ai_proactive_user_location"));
        assertTrue(sql.contains("ai_proactive_schedule"));
        assertTrue(sql.contains("'briefing'"));
        assertTrue(sql.contains("'weekdays'"));
        assertTrue(master.indexOf("202608131200.sql") > master.indexOf("202608121800.sql"));
    }

    @Test
    void terminalDirectoryIsUniqueAndDisplayNamesAndPlaceCatalogAreMigrated() throws Exception {
        String routableSql = DeviceDao.class.getMethod("selectRoutableByUser", Long.class)
                .getAnnotation(Select.class).value()[0];
        assertTrue(routableSql.contains("NOT EXISTS"));
        assertTrue(routableSql.contains("EXISTS"));
        assertFalse(routableSql.contains("LEFT JOIN ai_mobile_instance"));
        String sql = Files.readString(Path.of("src/main/resources/db/changelog/202608131900.sql"));
        String master = Files.readString(Path.of(
                "src/main/resources/db/changelog/db.changelog-master.yaml"));
        assertTrue(sql.contains("display_name"));
        assertTrue(sql.contains("ai_proactive_place_catalog"));
        assertTrue(sql.contains("source_mobile_instance_id"));
        assertTrue(master.indexOf("202608131900.sql") > master.indexOf("202608131800.sql"));
    }

    @Test
    void routingFreshnessUsesDatabaseClockAndScheduleWireTimesAreEpochMillis() throws Exception {
        String freshSql = ProactiveDeliveryRoutingDao.class.getMethod("selectFreshLocation",
                Long.class, String.class).getAnnotation(Select.class).value()[0];
        assertTrue(freshSql.contains("CURRENT_TIMESTAMP(3)"));
        assertTrue(freshSql.contains("INTERVAL 12 HOUR"));

        ObjectMapper mapper = new ObjectMapper();
        var register = mapper.readValue("""
                {"source_mac_address":"AA","source_schedule_id":"12","kind":"briefing",
                "label":"早报","scheduled_at":1786578000000,"recurrence":"weekdays",
                "weekdays":[1,2,3,4,5]}
                """, ProactiveScheduleDTOs.Register.class);
        assertEquals(1786578000000L, register.getScheduledAt());
        assertEquals(List.of(1,2,3,4,5), register.getWeekdays());
        String viewJson = mapper.writeValueAsString(new ProactiveScheduleDTOs.View("id", 7L,
                "d1", "12", "alarm", "起床", "once", List.of(), List.of(), null, 1786578000000L,
                "scheduled", 1786578000000L, null, null, 1, null));
        assertTrue(viewJson.contains("\"scheduled_at\":1786578000000"));
        assertTrue(!viewJson.contains("2026-"));
    }

    @Test
    void sourceTriggerAllowsBriefingWithoutLabelButRequiresAlarmLabel() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        ProactiveScheduleDTOs.SourceTrigger briefing = new ProactiveScheduleDTOs.SourceTrigger();
        briefing.setSourceMacAddress("AA"); briefing.setSourceScheduleId("7");
        briefing.setKind("briefing"); briefing.setTriggeredAt(1786578000000L);
        briefing.setSections(List.of("weather","news")); briefing.setLocation("广州");
        assertTrue(validator.validate(briefing).isEmpty());

        ProactiveScheduleDTOs.SourceTrigger alarm = new ProactiveScheduleDTOs.SourceTrigger();
        alarm.setSourceMacAddress("AA"); alarm.setSourceScheduleId("8");
        alarm.setKind("alarm"); alarm.setTriggeredAt(1786578000000L);
        assertFalse(validator.validate(alarm).isEmpty());

        ProactiveScheduleDTOs.SourceTrigger briefingWithoutLocation = new ProactiveScheduleDTOs.SourceTrigger();
        briefingWithoutLocation.setSourceMacAddress("AA");
        briefingWithoutLocation.setSourceScheduleId("9");
        briefingWithoutLocation.setKind("briefing");
        briefingWithoutLocation.setTriggeredAt(1786578000000L);
        briefingWithoutLocation.setSections(List.of("weather"));
        assertFalse(validator.validate(briefingWithoutLocation).isEmpty());

        alarm.setLabel("起床"); alarm.setSections(List.of("news"));
        assertFalse(validator.validate(alarm).isEmpty());

        ProactiveScheduleDTOs.SourceAction stop = new ProactiveScheduleDTOs.SourceAction();
        stop.setSourceMacAddress("AA"); stop.setSourceScheduleId("8"); stop.setAction("stop");
        assertTrue(validator.validate(stop).isEmpty());
        stop.setAction("snooze");
        assertFalse(validator.validate(stop).isEmpty());
    }

    @Test
    void scheduleLabelsUseEightyUnicodeCodePointsWithoutTruncation() {
        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        String eightyEmoji="😀".repeat(80);
        ProactiveScheduleDTOs.Register register=new ProactiveScheduleDTOs.Register();
        register.setSourceMacAddress("AA"); register.setSourceScheduleId("1");
        register.setKind("alarm"); register.setScheduledAt(1_786_578_000_000L);
        register.setLabel(eightyEmoji);
        assertTrue(validator.validate(register).isEmpty());
        register.setLabel("😀".repeat(81));
        assertFalse(validator.validate(register).isEmpty());

        ProactiveScheduleDTOs.SourceTrigger trigger=new ProactiveScheduleDTOs.SourceTrigger();
        trigger.setSourceMacAddress("AA"); trigger.setSourceScheduleId("1");
        trigger.setKind("alarm"); trigger.setTriggeredAt(1_786_578_000_000L);
        trigger.setLabel(eightyEmoji);
        assertTrue(validator.validate(trigger).isEmpty());
        trigger.setLabel("字".repeat(81));
        assertFalse(validator.validate(trigger).isEmpty());
    }

    @Test
    void onlyConfiguredAuthorityCanCreateOrRenameObservedPlace() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        ProactiveDeliveryRoutingDao routingDao = mock(ProactiveDeliveryRoutingDao.class);
        MobileInstanceDao mobileDao = mock(MobileInstanceDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        ProactiveDeliveryRoutingService service = new ProactiveDeliveryRoutingService(
                deviceDao, routingDao, mobileDao, userDao);
        when(userDao.selectIdForUpdate(7L)).thenReturn(7L);
        MobileInstanceEntity mobile = new MobileInstanceEntity();
        mobile.setUserId(7L); mobile.setMobileInstanceId("mob_authority");
        when(mobileDao.selectCanonicalByInstanceForUpdate("mob_authority")).thenReturn(mobile);
        when(routingDao.selectPlaceCatalogForUpdate(7L)).thenReturn(List.of());

        service.recordLocation(7L, "mob_authority", "place_12345678", "家",
                "entered", new java.util.Date(1_000));

        verify(routingDao).upsertObservedPlace(7L, "mob_authority", "place_12345678", "家");
        verify(routingDao).recordAuthoritativeLocation(7L, "mob_authority",
                "place_12345678", "entered", new java.util.Date(1_000));
        try {
            String sql = ProactiveDeliveryRoutingDao.class.getMethod("upsertObservedPlace",
                    Long.class, String.class, String.class, String.class)
                    .getAnnotation(org.apache.ibatis.annotations.Insert.class).value()[0];
            assertTrue(sql.contains("location_authority_mobile_instance_id=#{mobileInstanceId}"));
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    @Test
    void savedPlaceDirectoryIsAvailableBeforeAnyTransitionAndRemovesOnlyItsPhoneEntries() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        ProactiveDeliveryRoutingDao routingDao = mock(ProactiveDeliveryRoutingDao.class);
        MobileInstanceDao mobileDao = mock(MobileInstanceDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        ProactiveDeliveryRoutingService service = new ProactiveDeliveryRoutingService(
                deviceDao, routingDao, mobileDao, userDao);
        when(userDao.selectIdForUpdate(7L)).thenReturn(7L);
        MobileInstanceEntity mobile = new MobileInstanceEntity();
        mobile.setUserId(7L); mobile.setMobileInstanceId("mob_a");
        when(mobileDao.selectCanonicalByInstanceForUpdate("mob_a")).thenReturn(mobile);
        when(routingDao.selectPlaceCatalogForUpdate(7L)).thenReturn(List.of());
        ProactiveDeliveryRoutingDTOs.PlaceDirectoryUpdate update =
                new ProactiveDeliveryRoutingDTOs.PlaceDirectoryUpdate();
        update.setVersion(1);
        var first = new ProactiveDeliveryRoutingDTOs.PlaceDirectoryItem();
        first.setPlaceId("place_12345678"); first.setPlaceName(" 家 ");
        var second = new ProactiveDeliveryRoutingDTOs.PlaceDirectoryItem();
        second.setPlaceId("place_87654321"); second.setPlaceName("公司");
        update.setPlaces(List.of(first, second));
        when(routingDao.selectPlaceDirectory(7L)).thenReturn(List.of(
                new ProactiveDeliveryRoutingDao.PlaceCatalogRow(7L, "place_12345678",
                        "家", "mob_a", "[]")));

        var view = service.syncPlaceDirectory(7L, "mob_a", update);

        verify(routingDao).upsertPlaceCatalog(7L, "mob_a", "place_12345678", "家");
        verify(routingDao).upsertPlaceCatalog(7L, "mob_a", "place_87654321", "公司");
        verify(routingDao).deleteMissingPlaceCatalog(eq(7L), eq("mob_a"), any());
        assertEquals("家", view.places().getFirst().placeName());

        update.setPlaces(List.of(first, first));
        org.junit.jupiter.api.Assertions.assertThrows(xiaozhi.common.exception.RenException.class,
                () -> service.syncPlaceDirectory(7L, "mob_a", update));
        verify(routingDao, times(3)).upsertPlaceCatalog(any(), any(), any(), any());
    }

    @Test
    void placeDirectoryRejectsCrossPhoneTakeoverAndRemovesStaleRouting() {
        DeviceDao deviceDao = mock(DeviceDao.class);
        ProactiveDeliveryRoutingDao routingDao = mock(ProactiveDeliveryRoutingDao.class);
        MobileInstanceDao mobileDao = mock(MobileInstanceDao.class);
        SysUserDao userDao = mock(SysUserDao.class);
        when(userDao.selectIdForUpdate(7L)).thenReturn(7L);
        MobileInstanceEntity mobile = new MobileInstanceEntity();
        mobile.setUserId(7L); mobile.setMobileInstanceId("mob_b");
        when(mobileDao.selectCanonicalByInstanceForUpdate("mob_b")).thenReturn(mobile);
        ProactiveDeliveryRoutingService service = new ProactiveDeliveryRoutingService(
                deviceDao, routingDao, mobileDao, userDao);
        var ownedByA = new ProactiveDeliveryRoutingDao.PlaceCatalogRow(
                7L, "place_12345678", "家", "mob_a", "[]");
        when(routingDao.selectPlaceCatalogForUpdate(7L)).thenReturn(List.of(ownedByA));
        var takeover = new ProactiveDeliveryRoutingDTOs.PlaceDirectoryUpdate();
        takeover.setVersion(1);
        var place = new ProactiveDeliveryRoutingDTOs.PlaceDirectoryItem();
        place.setPlaceId("place_12345678"); place.setPlaceName("另一个家");
        takeover.setPlaces(List.of(place));
        org.junit.jupiter.api.Assertions.assertThrows(xiaozhi.common.exception.RenException.class,
                () -> service.syncPlaceDirectory(7L, "mob_b", takeover));
        verify(routingDao, never()).upsertPlaceCatalog(any(), any(), any(), any());

        mobile.setMobileInstanceId("mob_a");
        when(mobileDao.selectCanonicalByInstanceForUpdate("mob_a")).thenReturn(mobile);
        when(routingDao.selectPlaceDirectory(7L)).thenReturn(List.of());
        var empty = new ProactiveDeliveryRoutingDTOs.PlaceDirectoryUpdate();
        empty.setVersion(1); empty.setPlaces(List.of());
        service.syncPlaceDirectory(7L, "mob_a", empty);
        verify(routingDao).clearRemovedFixedPlaces(7L, List.of("place_12345678"));
        verify(routingDao).deleteRemovedPlaceRoutes(7L, List.of("place_12345678"));
        verify(routingDao).deleteMissingPlaceCatalog(7L, "mob_a", List.of());
    }

    @Test
    void scheduleSyncMigrationHasRevisionTombstoneAndActionCursor() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/changelog/202608131800.sql"));
        assertTrue(sql.contains("next_trigger_at"));
        assertTrue(sql.contains("sync_revision"));
        assertTrue(sql.contains("deleted_at"));
        assertTrue(sql.contains("CHAR_LENGTH(`label`) BETWEEN 1 AND 80"));
        assertTrue(sql.contains("ai_proactive_schedule_revision"));
        assertTrue(sql.contains("INSERT INTO `ai_proactive_schedule_revision`"));
        assertTrue(sql.contains("SET s.sync_revision=r.revision"));
        assertTrue(sql.contains("ai_proactive_schedule_action"));
        assertTrue(sql.contains("ai_proactive_schedule_device_cursor"));
        String dueSql = ProactiveScheduleDao.class.getMethod("selectDueForUpdate",String.class)
                .getAnnotation(Select.class).value()[0];
        assertTrue(dueSql.contains("CURRENT_TIMESTAMP(3)"));
        assertTrue(dueSql.contains("SKIP LOCKED"));
    }

    private DeviceEntity device(String id, Long userId) {
        DeviceEntity device = new DeviceEntity();
        device.setId(id);
        device.setUserId(userId);
        device.setMacAddress("mac-" + id);
        return device;
    }
}
