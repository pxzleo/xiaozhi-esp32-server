package xiaozhi.modules.mobile;

import lombok.Data;

@Data
public class MobileEventAuditRow {
    private String mobileInstanceId;
    private String deviceId;
    private String eventId;
    private String eventType;
    private String sourcePackage;
    private String eventState;
    private String summary;
    private String category;
    private String severity;
    private Double confidence;
    private String spokenSummary;
    private String reasonCode;
    private String processingStatus;
    private Long occurredAtEpochMillis;
    private Long createdAtEpochMillis;
    private Long processedAtEpochMillis;
    private String proactiveEventId;
    private String deliveryStatus;
}
