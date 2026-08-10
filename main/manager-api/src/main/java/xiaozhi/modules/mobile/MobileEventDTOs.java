package xiaozhi.modules.mobile;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MobileEventDTOs {
    private MobileEventDTOs() {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BatchRequest(int version, @NotEmpty @Size(max = 50) List<@NotNull @Valid CandidateEvent> events) {
        @JsonAnySetter public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机事件批次包含未知字段");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CandidateEvent(
            int version,
            @JsonProperty("event_id") @NotBlank @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String eventId,
            @JsonProperty("mobile_instance_id") @NotBlank @Pattern(regexp = "^mob_[0-9a-f]{32}$") String mobileInstanceId,
            @NotBlank @Pattern(regexp = "^(notification\\.state_changed|location\\.transition)$") String type,
            @JsonProperty("occurred_at") @NotNull Instant occurredAt,
            @NotNull @Valid EventSource source,
            @NotBlank @Pattern(regexp = "^(posted|updated|removed|entered|exited|dwelled)$") String state,
            @NotBlank @Size(max = 200) String summary,
            @NotNull @Size(max = 8) Map<@Pattern(regexp = "^(category|sender_hint|thread_hint|place_id|place_name|transition)$") String,
                    @Size(max = 80) String> entities,
            @JsonProperty("dedupe_key") @NotBlank @Pattern(regexp = "^sha256:[0-9a-f]{64}$") String dedupeKey,
            @JsonProperty("expires_at") @NotNull Instant expiresAt,
            @JsonProperty("privacy_level") @NotBlank @Pattern(regexp = "^(low|medium|high)$") String privacyLevel,
            @NotNull @Size(max = 4) Map<@Pattern(regexp = "^(rule_id|transition|notification_key_hash)$") String,
                    @Size(max = 100) String> evidence) {
        @JsonAnySetter public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机候选事件包含未知字段");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EventSource(
            @NotBlank @Pattern(regexp = "^(notification|location)$") String kind,
            @JsonProperty("package") @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_.]{2,199}$") String packageName,
            @Size(max = 100) String channel) {
        @JsonAnySetter public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机事件来源包含未知字段");
        }
    }

    public record BatchResponse(int version, List<EventResult> results,
            @JsonProperty("retry_after_seconds") int retryAfterSeconds) {}
    public record EventResult(@JsonProperty("event_id") String eventId, String status,
            @JsonProperty("reason_code") String reasonCode) {}

    public record ConfigResponse(int version, @JsonProperty("notification_gateway") NotificationConfig notificationGateway,
            @JsonProperty("location_gateway") @JsonInclude(JsonInclude.Include.NON_NULL) LocationConfig locationGateway) {}
    public record NotificationConfig(boolean available, @JsonProperty("max_summary_length") int maxSummaryLength,
            @JsonProperty("batch_size") int batchSize, List<String> categories) {}
    public record LocationConfig(boolean available) {}

    public record StatusResponse(int version, List<EventStatus> events) {}
    public record EventStatus(@JsonProperty("event_id") String eventId, String status,
            @JsonProperty("reason_code") String reasonCode, @JsonProperty("updated_at") Instant updatedAt) {}
}
