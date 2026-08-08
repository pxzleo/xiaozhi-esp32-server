package xiaozhi.modules.device.proactive;

import java.time.LocalTime;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import xiaozhi.modules.device.proactive.ProactiveEnums.DeliveryStatus;
import xiaozhi.modules.device.proactive.ProactiveEnums.EventType;
import xiaozhi.modules.device.proactive.ProactiveEnums.HabitType;
import xiaozhi.modules.device.proactive.ProactiveEnums.Mode;
import xiaozhi.modules.device.proactive.ProactiveEnums.Outcome;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;
import xiaozhi.modules.device.proactive.ProactiveEnums.MonitorType;

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
        @Size(max = 8, message = "allowed_topics最多8项")
        private Set<@NotNull Topic> allowedTopics = Set.of();
        @JsonProperty("blocked_topics")
        @Size(max = 8, message = "blocked_topics最多8项")
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
                case ACTIVE -> dailyLimit >= 1 && dailyLimit <= 5;
                case AGGRESSIVE -> dailyLimit == 0;
            };
        }

        @AssertTrue(message = "allowed_topics与blocked_topics不能重叠")
        public boolean isTopicsDisjoint() {
            if (allowedTopics == null || blockedTopics == null) return true;
            return allowedTopics.stream().noneMatch(blockedTopics::contains);
        }
    }

    @Data
    public static class WeatherMonitorConfig extends StrictRequest {
        @NotBlank @Pattern(regexp = "agent_plugin", message = "weather source必须为agent_plugin")
        private String source = "agent_plugin";
        @JsonProperty("hazard_types") @NotNull @Size(max = 16)
        private List<@NotBlank @Size(max = 32) String> hazardTypes = List.of();
        @JsonProperty("official_min_severity") @NotBlank
        @Pattern(regexp = "advisory|watch|warning|emergency")
        private String officialMinSeverity = "warning";
        @JsonProperty("precip_probability") @Min(0) @Max(100)
        private int precipProbability = 70;
        @JsonProperty("wind_speed_kmh") @Min(0) @Max(300)
        private int windSpeedKmh = 62;
        @JsonProperty("high_temp_c") @Min(-50) @Max(60)
        private int highTempC = 35;
        @JsonProperty("low_temp_c") @Min(-50) @Max(60)
        private int lowTempC = 0;
        @JsonProperty("temp_drop_24h_c") @Min(0) @Max(60)
        private int tempDrop24hC = 8;
        @JsonProperty("forecast_hours") @Min(1) @Max(168)
        private int forecastHours = 6;
        @JsonProperty("cooldown_minutes") @Min(1) @Max(10080)
        private int cooldownMinutes = 720;

        @AssertTrue(message = "low_temp_c必须小于high_temp_c")
        public boolean isTemperatureRangeValid() { return lowTempC < highTempC; }
    }

    @Data
    public static class NewsMonitorConfig extends StrictRequest {
        @JsonProperty("source_mode") @NotBlank
        @Pattern(regexp = "agent_plugin", message = "news source_mode必须为agent_plugin")
        private String sourceMode = "agent_plugin";
        @NotNull @Size(max = 16)
        private List<@NotBlank @Size(max = 200) String> sources = List.of();
        @NotNull @Size(max = 16)
        private List<@NotBlank @Size(max = 64) String> categories = List.of();
        @DecimalMin("0.50") @DecimalMax("1.00")
        private double confidence = 0.85;
        @JsonProperty("cooldown_minutes") @Min(1) @Max(10080)
        private int cooldownMinutes = 120;
        @JsonProperty("dedupe_hours") @Min(1) @Max(720)
        private int dedupeHours = 24;
        @NotBlank @Pattern(regexp = "domestic_and_international")
        private String scope = "domestic_and_international";
    }

    @Data
    public static class MonitorSetting<C> extends StrictRequest {
        @NotNull private Boolean enabled;
        @JsonProperty("interval_minutes") @NotNull @Min(5) @Max(1440)
        private Integer intervalMinutes;
        @NotNull @Valid private C config;
    }

    @Data
    public static class MonitorsUpdate extends StrictRequest {
        @NotNull @Valid private MonitorSetting<WeatherMonitorConfig> weather;
        @NotNull @Valid private MonitorSetting<NewsMonitorConfig> news;
    }

    public record MonitorView<C>(MonitorType type, boolean enabled,
            @JsonProperty("interval_minutes") int intervalMinutes, C config,
            Map<String, Object> state,
            @JsonProperty("last_success_at") Date lastSuccessAt,
            @JsonProperty("next_check_at") Date nextCheckAt,
            @JsonProperty("last_error_code") String lastErrorCode,
            @JsonProperty("last_probe_at") Date lastProbeAt,
            int version, @JsonProperty("updated_at") Date updatedAt) {}

    public record MonitorsView(@JsonProperty("device_id") String deviceId,
            MonitorView<WeatherMonitorConfig> weather,
            MonitorView<NewsMonitorConfig> news) {}

    public record PendingEnvelope(boolean pending,
            @JsonProperty("event_id") String eventId, Topic topic, Priority priority,
            @JsonProperty("created_at") Date createdAt,
            @JsonProperty("expires_at") Date expiresAt,
            @JsonProperty("retry_after_seconds") int retryAfterSeconds) {}

    @Data
    public static class MonitorLeaseRequest extends StrictRequest {
        @JsonProperty("lease_owner") @NotBlank @Size(max = 64) private String leaseOwner;
        @Min(1) @Max(100) private int limit = 20;
    }

    public record MonitorTask(@JsonProperty("device_id") String deviceId,
            @JsonProperty("mac_address") String macAddress,
            @JsonProperty("monitor_type") MonitorType monitorType,
            @JsonProperty("interval_minutes") int intervalMinutes,
            Map<String, Object> config, Map<String, Object> state,
            @JsonProperty("lease_owner") String leaseOwner,
            @JsonProperty("lease_token") String leaseToken,
            @JsonProperty("lease_until") Date leaseUntil) {}

    @Data
    public static class MonitorComplete extends StrictRequest {
        @JsonProperty("device_id") @NotBlank @Size(max = 32) private String deviceId;
        @JsonProperty("monitor_type") @NotNull private MonitorType monitorType;
        @JsonProperty("lease_owner") @NotBlank @Size(max = 64) private String leaseOwner;
        @JsonProperty("lease_token") @NotBlank @Size(max = 64) private String leaseToken;
        @NotNull private Boolean success;
        @NotNull @Size(max = 16) private Map<@NotBlank @Size(max = 64) String, Object> state;
        @JsonProperty("error_code") @Size(max = 64) private String errorCode;

        @AssertTrue(message = "成功时error_code必须为空，失败时error_code不能为空")
        public boolean isResultValid() {
            return success == null || success ? errorCode == null : errorCode != null && !errorCode.isBlank();
        }
    }

    @Data
    public static class ClassifierModelUpdate extends StrictRequest {
        @JsonProperty("model_id") @NotBlank @Size(max = 64) private String modelId;
    }

    public record ClassifierModelView(@JsonProperty("model_id") String modelId, boolean available) {}

    @Data
    public static class ClassifierEvaluate extends StrictRequest {
        @NotNull @Size(min = 1, max = 20) private List<@Valid NewsCandidate> candidates;
    }

    @Data
    public static class NewsCandidate extends StrictRequest {
        @NotBlank @Size(max = 200) private String title;
        @NotBlank @Size(max = 120) private String source;
        @Size(max = 500) private String facts;
    }

    public record ClassifierResult(String output) {}

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

        @AssertTrue(message = "外界监测事件的topic与event_type不匹配")
        public boolean isMonitorTopicValid() {
            if (eventType == null || topic == null) return true;
            return switch (eventType) {
                case WEATHER_ALERT -> topic == Topic.WEATHER;
                case NEWS_ALERT -> topic == Topic.NEWS;
                default -> true;
            };
        }
    }

    @Data
    public static class EventStatusUpdate extends StrictRequest {
        @NotBlank(message = "mac_address不能为空") @Size(max = 50)
        @JsonProperty("mac_address") private String macAddress;
        @NotNull @JsonProperty("delivery_status") private DeliveryStatus deliveryStatus;
        @NotNull private Outcome outcome;
        @Size(max = 64) @JsonProperty("claim_token") private String claimToken;
    }

    @Data
    public static class EventClaim extends StrictRequest {
        @NotBlank(message = "mac_address不能为空") @Size(max = 50)
        @JsonProperty("mac_address") private String macAddress;
        @NotBlank(message = "claim_token不能为空") @Size(max = 64)
        @JsonProperty("claim_token") private String claimToken;
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
            @JsonProperty("previous_daily_limit") Integer previousDailyLimit,
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
