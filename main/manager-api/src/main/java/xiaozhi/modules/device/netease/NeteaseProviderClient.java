package xiaozhi.modules.device.netease;

import com.fasterxml.jackson.databind.JsonNode;

public interface NeteaseProviderClient {
    JsonNode createQrKey();
    JsonNode createQr(String key);
    JsonNode checkQr(String key);
    JsonNode account(String cookie);
    JsonNode logout(String cookie);
}
