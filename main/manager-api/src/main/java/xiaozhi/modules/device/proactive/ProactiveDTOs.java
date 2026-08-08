package xiaozhi.modules.device.proactive;

import java.time.LocalTime;
import java.util.Date;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.HabitType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

public final class ProactiveDTOs {
    private ProactiveDTOs() {}

    public abstract static class StrictRequest {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("不支持的字段: " + name);
        }
    }

    @Data
    public static class PreferenceUpdate extends StrictRequest {
        @NotNull(message = "mode不能为空")
        private Mode mode;
        @JsonProperty("daily_limit")
        @Min(value = 0, message = "daily_limit不能小于0")
        @Max(value = 5, message = "daily_limit不能大于5")
        private Integer dailyLimit;
        @JsonProperty("quiet_start")
        private LocalTime quietStart;
        @JsonProperty("quiet_end")
        private LocalTime quietEnd;
        @JsonProperty("allowed_topics")
        @Size(max = 7, message = "allowed_topics最多7项")
        private Set<@NotNull Topic> allowedTopics = Set.of();
        @JsonProperty("blocked_topics")
        @Size(max = 7, message = "blocked_topics最多7项")
        private Set<@NotNull Topic> blockedTopics = Set.of();

        @AssertTrue(message = "安静时段必须同时提供起止时间且不能相同")
        public boolean isQuietWindowValid() {
            return quietStart == null && quietEnd == null
                    || quietStart != null && quietEnd != null && !quietStart.equals(quietEnd);
        }

        @AssertTrue(message = "daily_limit与mode不匹配")
        public boolean isDailyLimitValid() {
            if (mode == null || dailyLimit == null) return true;
            return switch (mode) {
                case TODAY_SILENT -> dailyLimit == 0;
                case CONSERVATIVE -> dailyLimit >= 1 && dailyLimit <= 1;
                case ACTIVE -> dailyLimit >= 1 && dailyLimit <= 3;
                case AGGRESSIVE -> dailyLimit >= 1 && dailyLimit <= 5;
            };
        }

        @AssertTrue(message = "allowed_topics与blocked_topics不能重叠")
        public boolean isTopicsDisjoint() {
            if (allowedTopics == null || blockedTopics == null) return true;
            return allowedTopics.stream().noneMatch(blockedTopics::contains);
        }
    }

    @Data
    public static class EventUpsert extends StrictRequest {
        @NotBlank(message = "mac_address不能为空") @Size(max = 50)
        @JsonProperty("mac_address") private String macAddress;
        @NotBlank(message = "event_id不能为空") @Size(max = 64)
        @JsonProperty("event_id") private String eventId;
        @NotNull private Topic topic;
        @NotNull private Priority priority;
        @NotBlank(message = "reason不能为空") @Size(max = 255) private String reason;
        @NotNull @JsonProperty("event_type") private EventType eventType;
        @NotNull @Size(max = 8) private Map<@NotBlank String, Object> payload;
        @NotNull @JsonProperty("created_at") private Date createdAt;
        @JsonProperty("expires_at") private Date expiresAt;
        @NotBlank(message = "dedupe_key不能为空") @Size(max = 128)
        @JsonProperty("dedupe_key") private String dedupeKey;
        @NotNull @JsonProperty("requires_response") private Boolean requiresResponse;

        @AssertTrue(message = "expires_at必须晚于created_at")
        public boolean isExpiryValid() {
            return expiresAt == null || createdAt == null || expiresAt.after(createdAt);
        }
    }

    @Data
    public static class EventStatusUpdate extends StrictRequest {
        @NotBlank(message = "mac_address不能为空") @Size(max = 50)
        @JsonProperty("mac_address") private String macAddress;
        @NotNull @JsonProperty("delivery_status") private DeliveryStatus deliveryStatus;
        @NotNull private Outcome outcome;
    }

    @Data
    public static class HabitObserve extends StrictRequest {
        @NotBlank(message = "mac_address不能为空") @Size(max = 50)
        @JsonProperty("mac_address") private String macAddress;
        @NotNull @JsonProperty("habit_type") private HabitType habitType;
        @NotBlank(message = "habit_key不能为空") @Size(max = 128)
        @JsonProperty("habit_key") private String habitKey;
        @Min(value = 1, message = "evidence_delta最小为1")
        @Max(value = 100, message = "evidence_delta最大为100")
        @JsonProperty("evidence_delta") private int evidenceDelta = 1;
        @NotNull @JsonProperty("seen_at") private Date seenAt;
        @NotNull @Size(max = 6) private Map<@NotBlank String, Object> payload;
    }

    public record PreferenceView(
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("mac_address") String macAddress,
            Mode mode,
            @JsonProperty("daily_limit") int dailyLimit,
            @JsonProperty("quiet_start") LocalTime quietStart,
            @JsonProperty("quiet_end") LocalTime quietEnd,
            @JsonProperty("allowed_topics") Set<Topic> allowedTopics,
            @JsonProperty("blocked_topics") Set<Topic> blockedTopics,
            @JsonProperty("previous_mode") Mode previousMode,
            @JsonProperty("silent_until") Date silentUntil,
            int version,
            @JsonProperty("updated_at") Date updatedAt) {}

    public record EventView(
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("mac_address") String macAddress,
            @JsonProperty("event_id") String eventId,
            Topic topic, Priority priority, String reason,
            @JsonProperty("event_type") EventType eventType,
            Map<String, Object> payload,
            @JsonProperty("created_at") Date createdAt,
            @JsonProperty("expires_at") Date expiresAt,
            @JsonProperty("dedupe_key") String dedupeKey,
            @JsonProperty("requires_response") boolean requiresResponse,
            @JsonProperty("delivery_status") DeliveryStatus deliveryStatus,
            Outcome outcome, @JsonProperty("delivered_at") Date deliveredAt) {}

    public record HabitView(
            Long id, @JsonProperty("device_id") String deviceId,
            @JsonProperty("mac_address") String macAddress,
            @JsonProperty("habit_type") HabitType habitType,
            @JsonProperty("habit_key") String habitKey,
            @JsonProperty("evidence_count") int evidenceCount,
            @JsonProperty("first_seen_at") Date firstSeenAt,
            @JsonProperty("last_seen_at") Date lastSeenAt,
            boolean suggested, boolean accepted, boolean dismissed,
            Map<String, Object> payload) {}
}
