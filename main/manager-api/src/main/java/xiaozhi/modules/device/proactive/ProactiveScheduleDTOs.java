package xiaozhi.modules.device.proactive;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
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
        private String label;
        @AssertTrue public boolean isLabelValid() {
            return ("briefing".equals(kind) || label != null && !label.isBlank())
                    && (label==null || label.strip().codePointCount(0,label.strip().length())<=80);
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
        @AssertTrue public boolean isRecurrenceValid() {
            if ("weekly".equals(recurrence)) return weekdays != null && !weekdays.isEmpty()
                    && weekdays.size()==new java.util.HashSet<>(weekdays).size();
            return weekdays != null && weekdays.isEmpty();
        }
    }
    @Data public static class Trigger extends StrictRequest {
        @NotNull @Positive @JsonProperty("triggered_at") private Long triggeredAt;
        @Size(max=50) @JsonProperty("requester_mac_address") private String requesterMacAddress;
    }
    @Data public static class SourceTrigger extends Trigger {
        @NotBlank @Size(max=50) @JsonProperty("source_mac_address") private String sourceMacAddress;
        @NotBlank @Size(max=64) @JsonProperty("source_schedule_id") private String sourceScheduleId;
        @NotBlank @Pattern(regexp="^(alarm|reminder|briefing)$") private String kind;
        private String label;
        @NotNull @Size(min=0,max=2) private java.util.List<@Pattern(regexp="^(weather|news)$") String>
                sections=java.util.List.of();
        @Size(max=40) private String location;
        @AssertTrue public boolean isLabelValid() {
            return ("briefing".equals(kind) || label != null && !label.isBlank())
                    && (label==null || label.strip().codePointCount(0,label.strip().length())<=80);
        }
        @AssertTrue public boolean isBriefingDataValid() {
            if (!"briefing".equals(kind)) return sections.isEmpty() && (location==null || location.isBlank());
            return !sections.isEmpty() && sections.size()==new java.util.HashSet<>(sections).size()
                    && (!sections.contains("weather") || location!=null && !location.isBlank());
        }
    }
    @Data public static class Action extends StrictRequest {
        @NotBlank @Pattern(regexp="^(stop|snooze|complete|delete)$") private String action;
        @Size(max=50) @JsonProperty("requester_mac_address") private String requesterMacAddress;
        @Positive @JsonProperty("snoozed_until") private Long snoozedUntil;
        @Positive private Integer version;
        @AssertTrue public boolean isSnoozeValid() {
            return "snooze".equals(action) == (snoozedUntil != null);
        }
    }
    @Data public static class SourceAction extends StrictRequest {
        @NotBlank @Size(max=50) @JsonProperty("source_mac_address") private String sourceMacAddress;
        @NotBlank @Size(max=64) @JsonProperty("source_schedule_id") private String sourceScheduleId;
        @NotBlank @Pattern(regexp="^(stop|snooze|complete|delete)$") private String action;
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
            @JsonProperty("next_trigger_at") Long nextTriggerAt,
            @JsonProperty("last_triggered_at") Long lastTriggeredAt,
            @JsonProperty("snoozed_until") Long snoozedUntil,int version,
            @JsonProperty("event_id") String eventId) {}

    public record SyncResponse(@JsonProperty("protocol_version") int protocolVersion,
            @JsonProperty("full_snapshot") boolean fullSnapshot, long cursor,
            @JsonProperty("has_more") boolean hasMore, java.util.List<SyncChange> changes) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SyncChange(long revision, String operation,
            @JsonProperty("schedule_id") String scheduleId,
            @JsonProperty("source_mac_address") String sourceMacAddress,
            @JsonProperty("is_local_source") boolean localSource,
            @JsonProperty("source_schedule_id") String sourceScheduleId,
            @JsonProperty("schedule_version") int scheduleVersion,
            String kind, String label, String recurrence, java.util.List<Integer> weekdays,
            java.util.List<String> sections, String location,
            @JsonProperty("scheduled_at") Long scheduledAt,
            @JsonProperty("next_trigger_at") Long nextTriggerAt, String state,
            @JsonProperty("last_triggered_at") Long lastTriggeredAt,
            @JsonProperty("snoozed_until") Long snoozedUntil,
            @JsonProperty("active_event_id") String activeEventId,
            @JsonProperty("active_delivery_status") String activeDeliveryStatus,
            @JsonProperty("updated_at") long updatedAt) {}

    public record ActionsResponse(@JsonProperty("protocol_version") int protocolVersion,
            long cursor, @JsonProperty("has_more") boolean hasMore,
            java.util.List<ActionChange> actions) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionChange(@JsonProperty("protocol_version") int protocolVersion,
            long revision, @JsonProperty("action_id") String actionId,
            @JsonProperty("schedule_id") String scheduleId,
            @JsonProperty("source_mac_address") String sourceMacAddress,
            @JsonProperty("is_local_source") boolean localSource,
            @JsonProperty("source_schedule_id") String sourceScheduleId,
            @JsonProperty("schedule_version") int scheduleVersion, String action,
            @JsonProperty("snoozed_until") Long snoozedUntil,
            @JsonProperty("next_trigger_at") Long nextTriggerAt,
            @JsonProperty("created_at") long createdAt) {}
    @Data public static class AckRequest extends StrictRequest {
        @NotNull @Min(1) @Max(1) @JsonProperty("protocol_version") private Integer protocolVersion;
        @NotNull @PositiveOrZero @JsonProperty("through_revision") private Long throughRevision;
    }
    public record AckResponse(@JsonProperty("protocol_version") int protocolVersion,
            @JsonProperty("acked_revision") long ackedRevision) {}
}
