package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.springframework.transaction.annotation.Transactional;

class MobileMigrationTest {
    @Test
    void migrationStoresOnlyCredentialHashAndHasDurableMessageDedupe() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608101200.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("`credential_hash` char(64)"));
            assertTrue(sql.contains("`credential_version` int unsigned NOT NULL"));
            assertTrue(sql.contains("UNIQUE KEY `uk_mobile_instance_owner_installation`"));
            assertTrue(sql.contains("PRIMARY KEY (`mobile_instance_id`, `message_id`)"));
            assertTrue(sql.contains("'PROCESSING', 'ACCEPTED'"));
            assertTrue(sql.contains("FOREIGN KEY (`mobile_instance_id`)"));
        }
    }

    @Test
    void m2MigrationHasPerInstanceIdempotencyAndStateFlowDedupe() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608111200.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("PRIMARY KEY (`mobile_instance_id`, `event_id`)"));
            assertTrue(sql.contains("UNIQUE KEY `uk_mobile_event_state_flow` (`mobile_instance_id`, `dedupe_key`)"));
            assertTrue(sql.contains("COMMENT '仅保存手机客户端已脱敏候选摘要'"));
            assertTrue(sql.contains("FOREIGN KEY (`mobile_instance_id`)"));
            assertTrue(sql.contains("CREATE TABLE `ai_mobile_event_audit`"));
            assertTrue(sql.contains("不保存正文"));
        }
    }

    @Test
    void masterAppliesM2OnlyAfterMobileInstanceMigration() throws Exception {
        String yaml;
        try (var stream = getClass().getResourceAsStream("/db/changelog/db.changelog-master.yaml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        int instance = yaml.indexOf("id: 202608101200");
        int events = yaml.indexOf("id: 202608111200");
        int proactive = yaml.indexOf("id: 202608111300");
        int locationState = yaml.indexOf("id: 202608111400");
        assertTrue(instance >= 0 && events > instance && locationState > proactive && proactive > events);
    }

    @Test
    void followupMigrationExpandsMobileEventStateWithoutEditingM2Migration() throws Exception {
        String original;
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608111200.sql")) {
            original = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(original.contains("('posted','updated','removed')"));
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608111400.sql")) {
            String followup = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(followup.contains("DROP CHECK `chk_mobile_event_state`"));
            assertTrue(followup.contains("'entered','exited','dwelled'"));
        }
    }

    @Test
    void processingMigrationAddsStrictLeaseClassificationAndAuditIndexesLast() throws Exception {
        String sql;
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608111600.sql")) {
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(sql.contains("`processing_status` varchar(16)"));
        assertTrue(sql.contains("'received','prefiltered','classified','ignored','converted','error'"));
        assertTrue(sql.contains("`processing_lease_owner` varchar(64)"));
        assertTrue(sql.contains("`processing_lease_token` char(36) CHARACTER SET ascii"));
        assertTrue(sql.contains("`processing_lease_until` datetime(3)"));
        assertTrue(sql.contains("`processing_attempt` int unsigned"));
        assertTrue(sql.contains("`next_attempt_at` datetime(3)"));
        assertTrue(sql.contains("`proactive_event_id` varchar(64) CHARACTER SET ascii"));
        assertTrue(sql.contains("CHECK (`confidence` IS NULL OR (`confidence` >= 0 AND `confidence` <= 1))"));
        assertTrue(sql.contains("idx_mobile_event_processing_due"));
        assertTrue(sql.contains("DROP CHECK `chk_proactive_event_dedupe_type`"));
        assertTrue(sql.contains("'WEATHER_ALERT', 'NEWS_ALERT', 'MOBILE_ALERT'"));

        String yaml;
        try (var stream = getClass().getResourceAsStream("/db/changelog/db.changelog-master.yaml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yaml.indexOf("id: 202608111600") > yaml.indexOf("id: 202608111400"));
        assertTrue(yaml.indexOf("id: 202608121200") > yaml.indexOf("id: 202608111600"));

        try (var stream = getClass().getResourceAsStream("/db/changelog/202608121200.sql")) {
            String settings = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(settings.contains("`alert_sensitivity` varchar(16) NOT NULL DEFAULT 'balanced'"));
            assertTrue(settings.contains("`alert_categories` varchar(160) NOT NULL"));
            assertTrue(settings.contains("security,call,parcel,appointment,message,other"));
        }
    }

    @Test
    void auditTimeMigrationAddsDefaultInstanceTimeIndexAfterExistingMobileMigrations() throws Exception {
        String yaml;
        try (var stream = getClass().getResourceAsStream("/db/changelog/db.changelog-master.yaml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yaml.indexOf("id: 202608121700") > yaml.indexOf("id: 202608121500"));
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608121700.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("idx_mobile_event_audit_time"));
            assertTrue(sql.contains("(`mobile_instance_id`, `occurred_at`, `event_id`)"));
        }
    }

    @Test
    void stableIdentityMigrationKeepsAliasesAndCanonicalHistory() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608121300.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("`stable_device_key` char(64)"));
            assertTrue(sql.contains("`canonical_instance_id` varchar(36)"));
            assertTrue(sql.contains("uk_mobile_instance_owner_stable_device"));
            assertTrue(sql.contains("idx_mobile_instance_canonical"));
            assertFalse(sql.contains("DELETE FROM"));
        }
    }

    @Test
    void locationAuditOnlyMigrationDismissesUndeliveredCopiesAndPreservesDeliveredHistory()
            throws Exception {
        String yaml;
        try (var stream = getClass().getResourceAsStream("/db/changelog/db.changelog-master.yaml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(yaml.indexOf("id: 202608121500") > yaml.indexOf("id: 202608121300"));
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608121500.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("me.event_type = 'location.transition'"));
            assertTrue(sql.contains("CREATE TEMPORARY TABLE `tmp_location_audit_groups`"));
            assertTrue(sql.contains("PRIMARY KEY (`user_id`, `delivery_group_key`)"));
            assertTrue(sql.contains("source.device_id = mi.device_id"));
            assertTrue(sql.contains("source.event_id = me.proactive_event_id"));
            assertTrue(sql.contains("location_group.user_id = copy_device.user_id"));
            assertTrue(sql.contains("copies.delivery_status IN ('PENDING', 'CLAIMED', 'FAILED')"));
            assertTrue(sql.contains("copies.delivery_status = 'DISMISSED'"));
            int dismissJoin = sql.indexOf("UPDATE `ai_device_proactive_event` copies");
            int dismissSet = sql.indexOf("SET copies.delivery_status = 'DISMISSED'", dismissJoin);
            assertFalse(sql.substring(dismissJoin, dismissSet).contains("has_delivered = 0"));
            assertTrue(sql.contains("delivered_device.user_id = location_group.user_id"));
            assertTrue(sql.contains("delivered_claim.user_id = location_group.user_id"));
            assertTrue(sql.contains("INSERT INTO `ai_proactive_delivery_claim`"));
            assertTrue(sql.contains("delivery_status = 'DELIVERED'"));
            assertTrue(sql.contains("location_group.has_delivered = 0"));
            assertTrue(sql.contains("dc.delivery_status = 'FAILED'"));
            assertTrue(sql.contains("reason_code = 'location_audit_only'"));
            assertFalse(sql.contains("DELETE FROM"));
        }
    }

    @Test
    void visibleDeviceAndAuditQueriesUseCanonicalMobileGroup() throws Exception {
        String deviceSql = String.join("\n", xiaozhi.modules.device.dao.DeviceDao.class
                .getDeclaredMethod("selectVisibleByUserAndAgent", Long.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        String eventSql = String.join("\n", MobileEventDao.class
                .getDeclaredMethod("pageAuditForUser", Long.class, String.class, String.class,
                        String.class, String.class, Long.class, Long.class,
                        int.class, long.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(deviceSql.contains("mi.canonical_instance_id=mi.mobile_instance_id"));
        assertTrue(deviceSql.contains("member.canonical_instance_id=mi.mobile_instance_id"));
        assertTrue(eventSql.contains("mi.canonical_instance_id=#{instanceId}"));
        assertTrue(eventSql.indexOf("dc.delivery_status = 'DELIVERED'")
                < eventSql.indexOf("p.expires_at IS NOT NULL"));
        String unbindSql = String.join("\n", xiaozhi.modules.device.dao.DeviceDao.class
                .getDeclaredMethod("selectMobileGroupDeviceIds", Long.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class).value());
        assertTrue(unbindSql.contains("alias.canonical_instance_id=selected.canonical_instance_id"));
    }

    @Test
    void processingClaimUsesDatabaseTimeCasAndAllowsExpiredLeaseTakeover() throws Exception {
        String candidates = String.join("\n", MobileEventDao.class
                .getMethod("selectProcessingCandidates", int.class)
                .getAnnotation(Select.class).value());
        String claim = String.join("\n", MobileEventDao.class
                .getMethod("claimProcessing", String.class, String.class, String.class, String.class)
                .getAnnotation(Update.class).value());

        assertTrue(candidates.contains("processing_lease_until < CURRENT_TIMESTAMP(3)"));
        assertTrue(claim.contains("processing_lease_until < CURRENT_TIMESTAMP(3)"));
        assertTrue(claim.contains("processing_attempt=processing_attempt+1"));
        assertTrue(claim.contains("expires_at > CURRENT_TIMESTAMP(3)"));
        String finishError = String.join("\n", MobileEventDao.class
                .getMethod("finishError", String.class, String.class, String.class,
                        String.class, long.class)
                .getAnnotation(Update.class).value());
        assertTrue(finishError.contains(
                "next_attempt_at=DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL #{delaySeconds} SECOND)"));
        assertFalse(finishError.contains("nextAttemptAt"));

        String latest = String.join("\n", MobileEventDao.class
                .getMethod("updateLatestState", MobileEventEntity.class)
                .getAnnotation(Update.class).value());
        assertTrue(latest.contains("mobile_instance_id=#{mobileInstanceId} AND dedupe_key=#{dedupeKey}"));
        assertTrue(latest.contains("occurred_at<#{occurredAt}"));
        assertTrue(latest.contains("ELSE 'received' END"));
        assertTrue(latest.contains("processing_lease_token=NULL"));
        String dismiss = String.join("\n", MobileEventDao.class
                .getMethod("supersedeUndeliveredMobileAlerts", String.class, String.class)
                .getAnnotation(Update.class).value());
        assertTrue(dismiss.contains("source.delivery_group_key=copies.delivery_group_key"));
        assertTrue(dismiss.contains("canonical.device_id=source.device_id"));
        assertTrue(dismiss.contains("copies_device.user_id=source_device.user_id"));
        assertTrue(dismiss.contains("copies.delivery_status IN ('PENDING','CLAIMED')"));
        assertTrue(dismiss.contains("WHEN copies.delivery_status='PENDING' THEN 'DISMISSED'"));
        assertTrue(dismiss.contains("WHEN copies.delivery_status='CLAIMED' THEN CURRENT_TIMESTAMP(3)"));
    }

    @Test
    void auditDeliveryStatusUsesDatabaseTimeDerivedExpressionForSelectAndFilter() throws Exception {
        String page = String.join("\n", MobileEventDao.class.getMethod("pageAuditForUser",
                Long.class, String.class, String.class, String.class, String.class,
                Long.class, Long.class, int.class, long.class)
                .getAnnotation(Select.class).value());
        String count = String.join("\n", MobileEventDao.class.getMethod("countAuditForUser",
                Long.class, String.class, String.class, String.class, String.class,
                Long.class, Long.class)
                .getAnnotation(Select.class).value());
        for (String sql : java.util.List.of(page, count)) {
            assertTrue(sql.contains("p.device_id=canonical.device_id"));
            assertTrue(sql.contains("p.expires_at &lt;= CURRENT_TIMESTAMP(3) THEN 'EXPIRED'"));
            assertTrue(sql.contains("p.delivery_status = 'DELIVERED' THEN 'DELIVERED'"));
            assertTrue(sql.contains("p.delivery_status IN ('FAILED','DISMISSED')"));
            assertTrue(sql.contains("DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 180 SECOND)"));
            assertTrue(sql.contains("THEN 'PENDING'"));
            assertTrue(sql.contains("=#{deliveryStatus}"));
        }
    }

    @Test
    void linkedDismissedCopyWinsOverAnotherDeviceFreshClaimInAuditAndFilter() throws Exception {
        String page = String.join("\n", MobileEventDao.class.getMethod("pageAuditForUser",
                Long.class, String.class, String.class, String.class, String.class,
                Long.class, Long.class, int.class, long.class)
                .getAnnotation(Select.class).value());
        String count = String.join("\n", MobileEventDao.class.getMethod("countAuditForUser",
                Long.class, String.class, String.class, String.class, String.class,
                Long.class, Long.class)
                .getAnnotation(Select.class).value());
        for (String sql : java.util.List.of(page, count)) {
            int linkedTerminal = sql.indexOf(
                    "p.delivery_status IN ('FAILED','DISMISSED') THEN p.delivery_status");
            int sharedClaim = sql.indexOf("COALESCE(dc.delivery_status,p.delivery_status)='CLAIMED'");
            assertTrue(linkedTerminal >= 0 && sharedClaim > linkedTerminal,
                    "当前关联副本终态必须先于共享领取账本派生");
            assertTrue(sql.indexOf("dc.delivery_status = 'DELIVERED'")
                    < linkedTerminal, "同用户真实投递账本必须优先于迁移后的 DISMISSED 来源副本");
        }
    }

    @Test
    void processingUsesSeparateShortSpringTransactionsAroundRemoteClassification() throws Exception {
        assertTrue(MobileEventProcessingService.class
                .getMethod("processBatch", String.class, int.class)
                .getAnnotation(Transactional.class) == null);
        assertTrue(MobileEventProcessingTransactionService.class
                .getMethod("claimAndRead", MobileEventEntity.class, String.class, String.class)
                .isAnnotationPresent(Transactional.class));
        assertTrue(MobileEventProcessingTransactionService.class
                .getMethod("convert", MobileEventProcessingTransactionService.ConversionCommand.class)
                .isAnnotationPresent(Transactional.class));

        String lock = String.join("\n", MobileEventDao.class
                .getMethod("selectByEventIdForUpdate", String.class, String.class)
                .getAnnotation(Select.class).value());
        assertTrue(lock.contains("FOR UPDATE"));
    }

    @Test
    void removedNotificationPreservesAnUnprocessedAuthoritativeSnapshot() throws Exception {
        String latest = String.join("\n", MobileEventDao.class
                .getMethod("updateLatestState", MobileEventEntity.class)
                .getAnnotation(Update.class).value());
        assertTrue(latest.contains("#{eventState}='removed'"));
        assertTrue(latest.contains("processing_status IN ('received','error')"));
        assertTrue(latest.contains("THEN event_state"));
        assertTrue(latest.contains("THEN processing_status"));
    }

    @Test
    void deliveredGroupMigrationDismissesResidualCopiesWithinSameUser() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608121800.sql")) {
            assertTrue(stream != null);
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("INSERT INTO ai_proactive_delivery_claim"));
            assertTrue(sql.contains("GROUP BY d2.user_id, e2.delivery_group_key"));
            assertTrue(sql.contains("e2.delivery_status='DELIVERED'"));
            assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
            assertTrue(sql.contains("ON DUPLICATE KEY UPDATE user_id=user_id"));
            assertFalse(sql.contains("ON DUPLICATE KEY UPDATE\n  delivery_status='DELIVERED'"));
            assertTrue(sql.contains("dc.delivery_status='DELIVERED'"));
            assertTrue(sql.contains("dc.user_id=d.user_id"));
            assertTrue(sql.contains("dc.delivery_group_key=e.delivery_group_key"));
            assertTrue(sql.contains("e.delivery_status IN ('PENDING','CLAIMED')"));
            assertTrue(sql.contains("e.delivery_status='DISMISSED'"));
            assertTrue(sql.contains("e.claim_token=NULL"));
            assertTrue(sql.contains("e.delivery_group_window_hours=0"));
            assertTrue(sql.contains("e.created_at < DATE_ADD(dc.event_created_at"));
        }
    }
}
