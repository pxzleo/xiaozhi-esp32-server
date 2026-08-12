package xiaozhi.modules.device.proactive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import xiaozhi.modules.device.dao.DeviceDao;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import jakarta.validation.Validation;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PreferenceUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.EventUpsert;
import xiaozhi.modules.device.proactive.ProactiveEnums.DedupePolicy;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

class ProactiveContractTest {
    @Test
    void deliveredContextOlderThanLeaseRemainsReadableWhileClaimedDoesNot() throws Exception {
        Method claimedContext = ProactiveEventDao.class.getMethod("selectClaimedMonitorEvent",
                String.class, String.class, String.class, java.util.Date.class);
        String sql = claimedContext.getAnnotation(Select.class).value()[0];
        int claimedBranch = sql.indexOf("e.delivery_status = 'CLAIMED'");
        int leaseGuard = sql.indexOf(
                "e.claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)");
        int deliveredBranch = sql.indexOf("OR e.delivery_status = 'DELIVERED'");
        assertTrue(claimedBranch >= 0 && leaseGuard > claimedBranch
                && deliveredBranch > leaseGuard);
        assertTrue(sql.contains("e.claim_token = #{claimToken}"));
        assertTrue(sql.contains("e.expires_at > CURRENT_TIMESTAMP(3)"));
    }

    @Test
    void externalRollingPoliciesHaveFixedWindowsAndLegacyEventsNeedNoPolicy() {
        EventUpsert news = new EventUpsert();
        news.setEventType(EventType.NEWS_ALERT);
        news.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        news.setDedupeWindowHours(24);
        assertTrue(news.isDedupePolicyValid());
        news.setDedupeWindowHours(23);
        assertFalse(news.isDedupePolicyValid());

        EventUpsert warning = new EventUpsert();
        warning.setEventType(EventType.WEATHER_ALERT);
        warning.setDedupePolicy(DedupePolicy.EVENT_ID);
        assertTrue(warning.isDedupePolicyValid());
        EventUpsert forecast = new EventUpsert();
        forecast.setEventType(EventType.WEATHER_ALERT);
        forecast.setDedupePolicy(DedupePolicy.ROLLING_WINDOW);
        forecast.setDedupeWindowHours(12);
        assertTrue(forecast.isDedupePolicyValid());

        EventUpsert reminder = new EventUpsert();
        reminder.setEventType(EventType.REMINDER);
        assertTrue(reminder.isDedupePolicyValid());
    }

    @Test
    void rollingDedupeLedgerUsesDatabaseClockUniqueLockAndHashOnlyStorage() throws Exception {
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/changelog/202608090130.sql"));
        assertTrue(migration.contains("PRIMARY KEY (`device_id`, `event_type`, `dedupe_hash`)"));
        assertTrue(migration.contains("`dedupe_hash` char(64)"));
        assertTrue(migration.contains("ON DELETE CASCADE"));
        assertFalse(migration.contains("title"));
        assertFalse(migration.contains("url"));

        Method insert = ProactiveEventDedupeDao.class.getMethod("insertIfAbsent",
                String.class, String.class, String.class);
        String insertSql = insert.getAnnotation(Insert.class).value()[0];
        assertTrue(insertSql.contains("INSERT IGNORE"));
        assertTrue(insertSql.contains("CURRENT_TIMESTAMP"));
        Method lock = ProactiveEventDedupeDao.class.getMethod("selectForUpdate",
                String.class, String.class, String.class);
        assertTrue(lock.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0]
                .contains("FOR UPDATE"));
        Method recent = ProactiveEventDedupeDao.class.getMethod("selectRecentEventId",
                String.class, String.class, String.class, int.class);
        String recentSql = recent.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(recentSql.contains("last_created_at > DATE_SUB(CURRENT_TIMESTAMP,"));
        assertTrue(recentSql.contains("INTERVAL #{windowHours} HOUR"));
        assertFalse(recentSql.contains("UTC_DATE"));
        Method mark = ProactiveEventDedupeDao.class.getMethod("markCreated",
                String.class, String.class, String.class, String.class);
        assertTrue(mark.getAnnotation(Update.class).value()[0]
                .contains("last_created_at = CURRENT_TIMESTAMP"));

        Method globalGate = ProactiveGlobalDao.class.getMethod(
                "selectExternalMonitoringValueForUpdate");
        String globalGateSql = globalGate.getAnnotation(Select.class).value()[0];
        assertTrue(globalGateSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(globalGateSql.contains("FOR UPDATE"));
    }

    @Test
    void ownerScopedDeliveryClaimIsDatabaseAtomicAcrossDeviceCopies() throws Exception {
        String migration = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/changelog/202608111300.sql"));
        assertTrue(migration.contains("PRIMARY KEY (`user_id`, `delivery_group_key`)"));
        assertTrue(migration.contains("WHEN `event_type` IN ('WEATHER_ALERT', 'NEWS_ALERT')"));
        assertTrue(migration.contains("THEN `dedupe_key` ELSE `event_id` END"));
        assertTrue(migration.contains("mobile_terminal_status"));
        assertTrue(migration.contains("mobile_terminal_reason"));

        Method claim = ProactiveDeliveryClaimDao.class.getMethod("claim", Long.class,
                String.class, String.class, String.class, String.class, java.util.Date.class,
                int.class);
        String sql = claim.getAnnotation(Update.class).value()[0];
        assertTrue(sql.contains("user_id = #{userId} AND delivery_group_key = #{groupKey}"));
        assertTrue(sql.contains("delivery_status IN ('PENDING', 'FAILED')"));
        assertTrue(sql.contains("claimed_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)"));
        assertTrue(sql.contains("WHEN claim_token = #{claimToken}"));
        assertTrue(sql.contains("THEN claimed_at"));
        assertTrue(sql.contains("ELSE CURRENT_TIMESTAMP(3) END"));
        assertFalse(sql.contains("#{now}"));
        assertFalse(sql.contains("#{leaseCutoff}"));
        assertTrue(sql.contains("#{eventCreatedAt} >= DATE_ADD(event_created_at"));

        Method pending = ProactiveEventDao.class.getMethod("selectPendingMonitorEvents",
                String.class);
        String pendingSql = pending.getAnnotation(Select.class).value()[0];
        String executablePending = pendingSql
                .replace("#{deviceId}", "'device-1'");
        CCJSqlParserUtil.parse(executablePending);
        assertTrue(pendingSql.contains("INNER JOIN ai_device d ON d.id = e.device_id"));
        assertTrue(pendingSql.contains("ai_proactive_delivery_claim"));
        assertTrue(pendingSql.contains("dc.user_id = d.user_id"));
        assertTrue(pendingSql.contains("dc.delivery_group_key = e.delivery_group_key"));

        Method page = ProactiveEventDao.class.getMethod("pageForUser", Long.class,
                String.class, String.class, String.class, String.class, int.class, long.class);
        String pageSql = page.getAnnotation(Select.class).value()[0];
        assertEquals(1, pageSql.split("INNER JOIN ai_device d", -1).length - 1);
        assertTrue(pageSql.contains("ai_proactive_delivery_claim dc"));
        assertTrue(pageSql.contains("WHEN dc.delivery_status='DELIVERED'"));
        assertTrue(pageSql.contains("e.created_at &lt; DATE_ADD("));
        assertTrue(pageSql.contains("AS effective_delivery_status"));

        Method count = ProactiveEventDao.class.getMethod("countForUser", Long.class,
                String.class, String.class, String.class, String.class);
        String countSql = count.getAnnotation(Select.class).value()[0];
        assertTrue(countSql.contains("ai_proactive_delivery_claim dc"));
        assertTrue(countSql.contains("WHEN dc.delivery_status='DELIVERED'"));
        assertTrue(countSql.contains("e.created_at &lt; DATE_ADD("));
        assertTrue(countSql.contains("END)=#{status}"));

        Method dismiss = ProactiveEventDao.class.getMethod("dismissSiblingCopiesAfterDelivery",
                Long.class, String.class, java.util.Date.class, int.class);
        String dismissSql = dismiss.getAnnotation(Update.class).value()[0];
        assertTrue(dismissSql.contains("d.user_id=#{userId}"));
        assertTrue(dismissSql.contains("e.delivery_group_key=#{groupKey}"));
        assertTrue(dismissSql.contains("e.delivery_status IN ('PENDING','CLAIMED')"));
        assertTrue(dismissSql.contains("e.claim_token=NULL"));
        assertTrue(dismissSql.contains("#{windowHours}=0"));
        assertFalse(dismissSql.contains("&lt;"));
        assertTrue(dismissSql.contains("e.created_at < DATE_ADD(#{eventCreatedAt}"));
        String executableDismiss = dismissSql
                .replace("#{userId}", "7")
                .replace("#{groupKey}", "'group-key'")
                .replace("#{eventCreatedAt}", "CURRENT_TIMESTAMP(3)")
                .replace("#{windowHours}", "24");
        CCJSqlParserUtil.parseStatements(executableDismiss);

        Method mobileTargets = DeviceDao.class.getMethod("selectMobileAlertTargetsForUpdate",
                Long.class, String.class, String.class);
        String targetSql = mobileTargets.getAnnotation(Select.class).value()[0];
        assertTrue(targetSql.contains("source.mobile_instance_id=#{mobileInstanceId}"));
        assertTrue(targetSql.contains("target_mobile.mobile_instance_id=source.canonical_instance_id"));
        assertTrue(targetSql.contains("target_mobile.mobile_instance_id IS NULL"));

        Method directComplete = ProactiveDeliveryClaimDao.class.getMethod("completeUnclaimed",
                Long.class, String.class, String.class, String.class, java.util.Date.class, int.class);
        String directCompleteSql = directComplete.getAnnotation(Update.class).value()[0];
        assertTrue(directCompleteSql.contains("delivery_status IN ('PENDING','FAILED')"));
        assertTrue(directCompleteSql.contains("delivery_status='DELIVERED' AND #{windowHours} > 0"));
        assertFalse(directCompleteSql.contains("delivery_status='CLAIMED'"));
        assertTrue(directCompleteSql.contains("user_id=#{userId}"));
        assertTrue(directCompleteSql.contains("delivery_group_key=#{groupKey}"));
    }

    @Test
    void migrationAddsOnlyScopedTablesAndRequiredIdempotencyKeys() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081200.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ai_device_proactive_preference"));
            assertTrue(sql.contains("ai_device_proactive_event"));
            assertTrue(sql.contains("ai_device_proactive_habit"));
            assertTrue(sql.contains("UNIQUE KEY `uk_ai_device_proactive_event_id` (`event_id`)"));
            assertTrue(sql.contains("UNIQUE KEY `uk_ai_device_proactive_event_dedupe` (`device_id`, `dedupe_key`)"));
            assertTrue(sql.contains("UNIQUE KEY `uk_ai_device_proactive_habit` (`device_id`, `habit_type`, `habit_key`)"));
            assertFalse(sql.toLowerCase().contains("chain_of_thought"));
        }
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081500.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN `previous_daily_limit`"));
            assertTrue(sql.contains("WHERE `mode` = 'TODAY_SILENT' AND `previous_daily_limit` IS NULL"));
            assertTrue(sql.contains("WHEN 'CONSERVATIVE' THEN 1"));
            assertTrue(sql.contains("WHEN 'ACTIVE' THEN 3"));
            assertTrue(sql.contains("WHEN 'AGGRESSIVE' THEN 5"));
            assertTrue(sql.contains("ELSE 5"));
            assertTrue(sql.contains("DROP INDEX `uk_ai_device_proactive_preference_mac`"));
            assertTrue(sql.contains("ADD KEY `idx_ai_device_proactive_preference_mac`"));
            assertTrue(sql.contains("ADD UNIQUE KEY `uk_ai_device_proactive_event_device_event` (`device_id`, `event_id`)"));
            assertTrue(sql.contains("ADD KEY `idx_ai_device_proactive_event_dedupe` (`device_id`, `dedupe_key`)"));
        }
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081700.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD KEY `idx_ai_device_user_id_id` (`user_id`, `id`)"));
        }
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608081900.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("ADD COLUMN `claim_token` varchar(64)"));
            assertTrue(sql.contains("ADD COLUMN `claimed_at` datetime"));
            assertTrue(sql.contains("idx_ai_device_proactive_event_claim"));
        }
    }

    @Test
    void preferenceValidationEnforcesModeLimitQuietWindowAndTopicSeparation() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            PreferenceUpdate request = new PreferenceUpdate();
            request.setMode(Mode.ACTIVE);
            request.setDailyLimit(6);
            request.setAllowedTopics(Set.of(Topic.MUSIC));
            request.setBlockedTopics(Set.of(Topic.MUSIC));
            assertFalse(validator.validate(request).isEmpty());

            request.setDailyLimit(5);
            request.setBlockedTopics(Set.of(Topic.WEATHER));
            assertTrue(validator.validate(request).isEmpty());

            request.setMode(Mode.AGGRESSIVE);
            request.setDailyLimit(1);
            assertFalse(validator.validate(request).isEmpty());
            request.setDailyLimit(0);
            assertTrue(validator.validate(request).isEmpty());
        }
    }

    @Test
    void unknownRequestFieldsAreRejectedEvenThoughGlobalMapperIsLenient() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        assertThrows(Exception.class, () -> mapper.readValue(
                "{\"mode\":\"aggressive\",\"hidden_prompt\":\"secret\"}", PreferenceUpdate.class));
    }

    @Test
    void modeWireContractUsesStrictLowercaseValues() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertEquals("\"aggressive\"", mapper.writeValueAsString(Mode.AGGRESSIVE));
        assertEquals(Mode.AGGRESSIVE, mapper.readValue("\"aggressive\"", Mode.class));
        assertThrows(Exception.class, () -> mapper.readValue("\"AGGRESSIVE\"", Mode.class));
    }

    @Test
    void habitObservationUsesAtomicMysqlIncrementAndEventStatusScopesDevice() throws Exception {
        Method observe = ProactiveHabitDao.class.getMethod("observeAtomic", ProactiveHabitEntity.class);
        String habitSql = observe.getAnnotation(Insert.class).value()[0];
        assertTrue(habitSql.contains("evidence_count = evidence_count + VALUES(evidence_count)"));
        assertTrue(habitSql.contains("ON DUPLICATE KEY UPDATE"));

        Method status = ProactiveEventDao.class.getMethod("updateStatusCas", String.class, String.class,
                String.class, String.class, String.class, String.class, java.util.Date.class);
        String eventSql = status.getAnnotation(Update.class).value()[0];
        assertTrue(eventSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
        assertTrue(eventSql.contains("delivery_status = #{expectedStatus}"));
        assertTrue(eventSql.contains("claim_token = #{claimToken}"));
        assertTrue(eventSql.contains("claim_token IS NULL OR"));
        assertTrue(eventSql.contains("claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)"));
        assertTrue(eventSql.contains("expires_at > CURRENT_TIMESTAMP(3)"));

        Method groupComplete = ProactiveDeliveryClaimDao.class.getMethod("complete", Long.class,
                String.class, String.class, String.class, java.util.Date.class);
        String groupCompleteSql = groupComplete.getAnnotation(Update.class).value()[0];
        assertTrue(groupCompleteSql.contains(
                "claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)"));

        Method claimedContext = ProactiveEventDao.class.getMethod("selectClaimedMonitorEvent",
                String.class, String.class, String.class, java.util.Date.class);
        String claimedContextSql = claimedContext.getAnnotation(Select.class).value()[0];
        assertTrue(claimedContextSql.contains(
                "claimed_at >= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)"));
        assertTrue(claimedContextSql.contains("e.delivery_status = 'CLAIMED'"));
        assertTrue(claimedContextSql.contains("OR e.delivery_status = 'DELIVERED'"));
        assertTrue(claimedContextSql.contains("expires_at > CURRENT_TIMESTAMP(3)"));

        Method findEvent = ProactiveEventDao.class.getMethod("selectByDeviceAndEventId",
                String.class, String.class);
        String findSql = findEvent.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(findSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
        assertFalse(findSql.contains(" OR "));

        Method restore = ProactivePreferenceDao.class.getMethod("restoreExpiredSilent",
                String.class, java.util.Date.class);
        String restoreSql = restore.getAnnotation(Update.class).value()[0];
        assertTrue(restoreSql.contains("WHEN 'AGGRESSIVE' THEN 0"));
        assertTrue(restoreSql.contains("previous_daily_limit = NULL"));

        Method normalize = ProactivePreferenceDao.class.getMethod("normalizeLegacyAggressiveLimit",
                String.class, Integer.class, Integer.class, java.util.Date.class);
        String normalizeSql = normalize.getAnnotation(Update.class).value()[0];
        assertTrue(normalizeSql.contains("SET daily_limit = 0"));
        assertTrue(normalizeSql.contains("version = version + 1"));
        assertTrue(normalizeSql.contains("mode = 'AGGRESSIVE'"));
        assertTrue(normalizeSql.contains("daily_limit = #{expectedDailyLimit}"));
        assertTrue(normalizeSql.contains("daily_limit BETWEEN 1 AND 5"));
        assertTrue(normalizeSql.contains("version = #{expectedVersion}"));
        String normalizeSet = normalizeSql.substring(normalizeSql.indexOf("SET "),
                normalizeSql.indexOf("\nWHERE"));
        assertEquals("SET daily_limit = 0, version = version + 1, updated_at = #{now}",
                normalizeSet);

        Method normalizePrevious = ProactivePreferenceDao.class.getMethod(
                "normalizeLegacyPreviousAggressiveLimit", String.class, Integer.class,
                Integer.class, java.util.Date.class);
        String normalizePreviousSql = normalizePrevious.getAnnotation(Update.class).value()[0];
        assertTrue(normalizePreviousSql.contains("SET previous_daily_limit = 0"));
        assertTrue(normalizePreviousSql.contains("mode = 'TODAY_SILENT'"));
        assertTrue(normalizePreviousSql.contains("previous_mode = 'AGGRESSIVE'"));
        assertTrue(normalizePreviousSql.contains("previous_daily_limit BETWEEN 1 AND 5"));
        String normalizePreviousSet = normalizePreviousSql.substring(
                normalizePreviousSql.indexOf("SET "), normalizePreviousSql.indexOf("\nWHERE"));
        assertEquals("SET previous_daily_limit = 0, version = version + 1, updated_at = #{now}",
                normalizePreviousSet);

        Method lockedPreference = ProactivePreferenceDao.class.getMethod(
                "selectByIdForUpdate", String.class);
        String lockedPreferenceSql = lockedPreference.getAnnotation(
                org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(lockedPreferenceSql.contains("device_id = #{deviceId}"));
        assertTrue(lockedPreferenceSql.contains("FOR UPDATE"));

        Method lockedFind = ProactiveEventDao.class.getMethod("selectByDeviceAndEventIdForUpdate",
                String.class, String.class);
        String lockedFindSql = lockedFind.getAnnotation(org.apache.ibatis.annotations.Select.class).value()[0];
        assertTrue(lockedFindSql.contains("device_id = #{deviceId} AND event_id = #{eventId}"));
        assertTrue(lockedFindSql.contains("FOR UPDATE"));
        assertFalse(lockedFindSql.contains(" OR "));

        Method insertIfAbsent = ProactiveEventDao.class.getMethod("insertIfAbsent", ProactiveEventEntity.class);
        String insertSql = insertIfAbsent.getAnnotation(Insert.class).value()[0];
        assertTrue(insertSql.contains("INSERT IGNORE INTO ai_device_proactive_event"));
        assertFalse(insertSql.contains("ON DUPLICATE KEY UPDATE"));

        Method claim = ProactiveEventDao.class.getMethod("claimPending", String.class,
                String.class, String.class);
        String claimSql = claim.getAnnotation(Update.class).value()[0];
        assertTrue(claimSql.contains("delivery_status = 'CLAIMED'"));
        assertTrue(claimSql.contains("delivery_status = 'PENDING'"));
        assertTrue(claimSql.contains("claim_token = #{claimToken}"));
        assertTrue(claimSql.contains("claimed_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)"));
        assertTrue(claimSql.contains("ELSE CURRENT_TIMESTAMP(3) END"));
        assertFalse(claimSql.contains("#{now}"));
        assertFalse(claimSql.contains("#{leaseCutoff}"));
        assertTrue(claimSql.contains("claimed_at IS NULL"));
        assertTrue(claimSql.contains("e.device_id = #{deviceId} AND e.event_id = #{eventId}"));
        assertTrue(claimSql.contains("LEFT JOIN ai_device_proactive_monitor m"));
        assertTrue(claimSql.contains("WHEN 'WEATHER_ALERT' THEN 'WEATHER'"));
        assertTrue(claimSql.contains("WHEN 'NEWS_ALERT' THEN 'NEWS'"));
        assertTrue(claimSql.contains("m.enabled = 1"));
        assertTrue(claimSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(claimSql.contains("LOWER(TRIM(g.param_value)) = 'true'"));
        assertTrue(claimSql.contains("e.event_type NOT IN ('WEATHER_ALERT', 'NEWS_ALERT')"));
        assertFalse(claimSql.contains("priority"));

        Method monitorRead = ProactiveEventDao.class.getMethod(
                "selectMonitorEventByMacAndEventId", String.class, String.class);
        String monitorReadSql = monitorRead.getAnnotation(Select.class).value()[0];
        assertTrue(monitorReadSql.contains("LEFT JOIN ai_device_proactive_monitor m"));
        assertTrue(monitorReadSql.contains("m.enabled = 1"));
        assertTrue(monitorReadSql.contains("proactive.external_monitoring_enabled"));
        assertTrue(monitorReadSql.contains("LOWER(TRIM(g.param_value)) = 'true'"));
        assertTrue(monitorReadSql.contains("WHEN 'WEATHER_ALERT' THEN 'WEATHER'"));
        assertTrue(monitorReadSql.contains("WHEN 'NEWS_ALERT' THEN 'NEWS'"));
        assertTrue(monitorReadSql.contains("e.event_type = 'MOBILE_ALERT'"));
        assertTrue(monitorReadSql.contains("OR (m.enabled = 1 AND LOWER(TRIM(g.param_value)) = 'true')"));
        assertTrue(monitorReadSql.contains("e.expires_at > CURRENT_TIMESTAMP(3)"));
        assertTrue(monitorReadSql.contains("e.delivery_status IN ('PENDING','CLAIMED')"));
    }
}
