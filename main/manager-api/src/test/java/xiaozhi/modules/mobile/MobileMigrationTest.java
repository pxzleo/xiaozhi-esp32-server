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
}
