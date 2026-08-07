package xiaozhi.modules.device.netease;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class NeteaseConfigurationTest {
    @Test
    void springAndAllInOneComposeExposeNeteaseApiEnvironmentVariable() throws Exception {
        String application = Files.readString(Path.of("src/main/resources/application.yml"));
        String compose = Files.readString(Path.of("../xiaozhi-server/docker-compose_all.yml"));

        assertTrue(application.contains("${NETEASE_MUSIC_API_BASE_URL:http://127.0.0.1:3000}"));
        assertTrue(application.contains(
                "${NETEASE_MUSIC_PUBLIC_BASE_URL:http://192.168.100.149/xiaozhi}"));
        assertTrue(compose.contains("- NETEASE_MUSIC_API_BASE_URL"));
        assertTrue(compose.contains("- NETEASE_MUSIC_PUBLIC_BASE_URL"));
        assertTrue(application.contains(
                "xiaozhi.modules.device.netease.DeviceNeteaseSessionDao: INFO"));
        assertTrue(application.contains(
                "xiaozhi.modules.device.netease.DeviceNeteaseAuthDao: INFO"));
    }
}
