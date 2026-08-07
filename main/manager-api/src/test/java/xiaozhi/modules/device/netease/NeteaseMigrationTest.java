package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class NeteaseMigrationTest {
    @Test
    void migrationSafelyRemovesHistoricalAgentCookie() throws Exception {
        try (var stream = getClass().getResourceAsStream("/db/changelog/202608071600.sql")) {
            String sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(sql.contains("JSON_VALID(`param_info`)"));
            assertTrue(sql.contains("JSON_REMOVE(`param_info`, '$.cookie')"));
            assertTrue(sql.contains("`plugin_id` = 'SYSTEM_PLUGIN_NETEASE_MUSIC'"));
        }
    }
}
