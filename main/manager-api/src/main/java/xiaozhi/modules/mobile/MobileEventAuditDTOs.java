package xiaozhi.modules.mobile;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class MobileEventAuditDTOs {
    private MobileEventAuditDTOs() {}

    public record AuditView(
            @JsonProperty("mobile_instance_id") String mobileInstanceId,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("event_id") String eventId,
            String type,
            @JsonProperty("source_package") String sourcePackage,
            String state,
            String summary,
            String category,
            String severity,
            Double confidence,
            @JsonProperty("spoken_summary") String spokenSummary,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("occurred_at") Date occurredAt,
            @JsonProperty("received_at") Date receivedAt,
            @JsonProperty("processed_at") Date processedAt,
            @JsonProperty("proactive_event_id") String proactiveEventId,
            @JsonProperty("delivery_status") String deliveryStatus) {}
}
