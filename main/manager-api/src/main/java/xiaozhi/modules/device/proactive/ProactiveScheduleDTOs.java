package xiaozhi.modules.device.proactive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import lombok.Data;

public final class ProactiveScheduleDTOs {
    private ProactiveScheduleDTOs() {}
    public abstract static class StrictRequest {
        @JsonAnySetter public void reject(String key, JsonNode value) {
            throw new IllegalArgumentException("共享日程包含未知字段: " + key);
        }
    }
    @Data public static class Register extends StrictRequest {
        @NotBlank @Size(max=50) @JsonProperty("source_mac_address") private String sourceMacAddress;
        @NotBlank @Size(max=64) @JsonProperty("source_schedule_id") private String sourceScheduleId;
        @NotBlank @Pattern(regexp="^(alarm|reminder|briefing)$") private String kind;
        @Size(max=120) private String label;
        @AssertTrue public boolean isLabelValid() {
            return "briefing".equals(kind) || label != null && !label.isBlank();
        }
        @NotNull @Positive @JsonProperty("scheduled_at") private Long scheduledAt;
        @NotBlank @Pattern(regexp="^(once|daily|weekdays|weekends|weekly)$")
        private String recurrence="once";
        @NotNull @Size(max=7) private java.util.List<@Min(1) @Max(7) Integer> weekdays=java.util.List.of();
        @NotNull @Size(min=0,max=2) private java.util.List<@Pattern(regexp="^(weather|news)$") String>
                sections=java.util.List.of();
        @Size(max=40) private String location;
        @AssertTrue public boolean isBriefingDataValid() {
            if (!"briefing".equals(kind)) return sections.isEmpty() && (location==null || location.isBlank());
            return !sections.isEmpty() && sections.size()==new java.util.HashSet<>(sections).size()
                    && (!sections.contains("weather") || location!=null && !location.isBlank());
        }
    }
    @Data public static class Trigger extends StrictRequest {
        @NotNull @Positive @JsonProperty("triggered_at") private Long triggeredAt;
    }
    @Data public static class SourceTrigger extends Trigger {
        @NotBlank @Size(max=50) @JsonProperty("source_mac_address") private String sourceMacAddress;
        @NotBlank @Size(max=64) @JsonProperty("source_schedule_id") private String sourceScheduleId;
        @NotBlank @Pattern(regexp="^(alarm|reminder|briefing)$") private String kind;
        @Size(max=120) private String label;
        @NotNull @Size(min=0,max=2) private java.util.List<@Pattern(regexp="^(weather|news)$") String>
                sections=java.util.List.of();
        @Size(max=40) private String location;
        @AssertTrue public boolean isLabelValid() {
            return "briefing".equals(kind) || label != null && !label.isBlank();
        }
        @AssertTrue public boolean isBriefingDataValid() {
            if (!"briefing".equals(kind)) return sections.isEmpty() && (location==null || location.isBlank());
            return !sections.isEmpty() && sections.size()==new java.util.HashSet<>(sections).size()
                    && (!sections.contains("weather") || location!=null && !location.isBlank());
        }
    }
    @Data public static class Action extends StrictRequest {
        @NotBlank @Pattern(regexp="^(stop|snooze|complete)$") private String action;
        @Positive @JsonProperty("snoozed_until") private Long snoozedUntil;
        private int version;
        @AssertTrue public boolean isSnoozeValid() {
            return "snooze".equals(action) == (snoozedUntil != null);
        }
    }
    @Data public static class SourceAction extends StrictRequest {
        @NotBlank @Size(max=50) @JsonProperty("source_mac_address") private String sourceMacAddress;
        @NotBlank @Size(max=64) @JsonProperty("source_schedule_id") private String sourceScheduleId;
        @NotBlank @Pattern(regexp="^(stop|snooze|complete)$") private String action;
        @Positive @JsonProperty("snoozed_until") private Long snoozedUntil;
        @AssertTrue public boolean isSnoozeValid() {
            return "snooze".equals(action) == (snoozedUntil != null);
        }
    }
    public record View(String id,@JsonProperty("user_id") long userId,
            @JsonProperty("source_device_id") String sourceDeviceId,
            @JsonProperty("source_schedule_id") String sourceScheduleId,String kind,String label,
            String recurrence, java.util.List<Integer> weekdays,
            java.util.List<String> sections, String location,
            @JsonProperty("scheduled_at") long scheduledAt,String state,
            @JsonProperty("last_triggered_at") Long lastTriggeredAt,
            @JsonProperty("snoozed_until") Long snoozedUntil,int version,
            @JsonProperty("event_id") String eventId) {}
}
