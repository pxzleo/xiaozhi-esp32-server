package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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

        String latest = String.join("\n", MobileEventDao.class
                .getMethod("updateLatestState", MobileEventEntity.class)
                .getAnnotation(Update.class).value());
        assertTrue(latest.contains("occurred_at<#{occurredAt}"));
        assertTrue(latest.contains("processing_status='received'"));
        assertTrue(latest.contains("processing_lease_token=NULL"));
        String dismiss = String.join("\n", MobileEventDao.class
                .getMethod("dismissPendingMobileAlerts", String.class, String.class)
                .getAnnotation(Update.class).value());
        assertTrue(dismiss.contains("source.delivery_group_key=copies.delivery_group_key"));
        assertTrue(dismiss.contains("copies.delivery_status='PENDING'"));
        assertTrue(dismiss.contains("copies.delivery_status='DISMISSED'"));
    }
}
