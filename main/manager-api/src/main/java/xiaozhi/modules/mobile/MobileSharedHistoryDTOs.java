package xiaozhi.modules.mobile;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class MobileSharedHistoryDTOs {
    private MobileSharedHistoryDTOs() {}
    public record Item(long id, @JsonProperty("session_id") String sessionId, String role,
            String content, @JsonProperty("created_at") long createdAt,
            @JsonProperty("source_device_id") String sourceDeviceId,
            @JsonProperty("source_device_alias") String sourceDeviceAlias,
            @JsonProperty("source_terminal_type") String sourceTerminalType) {}
    public record Response(int version, List<Item> items,
            @JsonProperty("next_before_id") Long nextBeforeId) {}
}
