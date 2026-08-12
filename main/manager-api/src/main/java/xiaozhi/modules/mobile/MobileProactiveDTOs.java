package xiaozhi.modules.mobile;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.AssertTrue;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PendingEnvelope;
import xiaozhi.modules.device.proactive.ProactiveEnums.Priority;
import xiaozhi.modules.device.proactive.ProactiveEnums.Topic;

public final class MobileProactiveDTOs {
    private MobileProactiveDTOs() {}

    public abstract static class StrictRequest {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("不支持的字段: " + name);
        }
    }

    public record PendingResponse(int version, boolean pending,
            @JsonProperty("event_id") String eventId, Topic topic, Priority priority,
            @JsonProperty("expires_at") Long expiresAt,
            @JsonProperty("retry_after_seconds") int retryAfterSeconds) {
        static PendingResponse from(PendingEnvelope envelope) {
            return new PendingResponse(1, envelope.pending(), envelope.eventId(), envelope.topic(),
                    envelope.priority(), envelope.expiresAt() == null ? null : envelope.expiresAt().getTime(),
                    envelope.retryAfterSeconds());
        }
    }

    public static final class QuietHoursRequest extends StrictRequest {
        public int version;
        private boolean quietStartPresent;
        private boolean quietEndPresent;
        @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
        public String quietStart;
        @Pattern(regexp = "^([01][0-9]|2[0-3]):[0-5][0-9]$")
        public String quietEnd;

        @JsonSetter("quiet_start")
        public void setQuietStart(String value) { quietStartPresent = true; quietStart = value; }
        @JsonSetter("quiet_end")
        public void setQuietEnd(String value) { quietEndPresent = true; quietEnd = value; }

        @AssertTrue(message = "安静时段必须同时提供起止时间且不能相同")
        public boolean isQuietWindowValid() {
            return quietStartPresent && quietEndPresent && (quietStart == null && quietEnd == null
                    || quietStart != null && quietEnd != null && !quietStart.equals(quietEnd));
        }
    }

    public record QuietHoursResponse(int version,
            @JsonProperty("quiet_start") String quietStart,
            @JsonProperty("quiet_end") String quietEnd,
            @JsonProperty("updated_at") long updatedAt) {}

    public static final class ClaimRequest extends StrictRequest {
        public int version;
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        @JsonProperty("claim_token") public String claimToken;
    }

    public static final class CompleteRequest extends StrictRequest {
        public int version;
        @NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
        @JsonProperty("claim_token") public String claimToken;
        @NotBlank @Pattern(regexp = "delivered|interrupted|failed") public String status;
        @Pattern(regexp = "phone_call|user_interrupt|audio_focus_lost|tts_error|notification_error|connection_error|expired")
        public String reason;

        @AssertTrue(message = "终态与reason不匹配")
        public boolean isReasonValid() {
            if (status == null) return true;
            return "delivered".equals(status) ? reason == null
                    : reason != null && !reason.isBlank();
        }
    }

    public record Followup(boolean enabled, String type,
            @JsonProperty("reference_id") String referenceId,
            @JsonProperty("reference_url") String referenceUrl,
            String source) {}

    public record ExternalContext(String title, String source, String facts,
            @JsonProperty("reference_url") String referenceUrl, boolean followup) {}

    public record ClaimResponse(int version,
            @JsonProperty("event_id") String eventId,
            @JsonProperty("claim_token") String claimToken,
            Topic topic, Priority priority, String title, String summary, String tts,
            String sensitivity, Followup followup,
            @JsonProperty("external_context") ExternalContext externalContext,
            @JsonProperty("expires_at") Long expiresAt) {}

    public record CompleteResponse(int version,
            @JsonProperty("event_id") String eventId, String status, String reason) {}
}
