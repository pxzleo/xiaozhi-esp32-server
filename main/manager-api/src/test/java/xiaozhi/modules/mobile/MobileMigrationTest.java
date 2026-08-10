package xiaozhi.modules.mobile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

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
}
