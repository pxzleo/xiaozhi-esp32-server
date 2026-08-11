package xiaozhi.modules.mobile;

import java.util.Date;

import lombok.Data;

@Data
public class MobileEventEntity {
    private String mobileInstanceId;
    private String eventId;
    private String dedupeKey;
    private String eventType;
    private String sourcePackage;
    private String sourceChannel;
    private String eventState;
    private String summary;
    private String entitiesJson;
    private String evidenceJson;
    private String privacyLevel;
    private String status;
    private String processingStatus;
    private String reasonCode;
    private String category;
    private String severity;
    private Double confidence;
    private String spokenSummary;
    private String proactiveEventId;
    private String processingLeaseOwner;
    private String processingLeaseToken;
    private Date processingLeaseUntil;
    private Integer processingAttempt;
    private Date nextAttemptAt;
    private Date processedAt;
    private Date occurredAt;
    private Date expiresAt;
    private Date createdAt;
    private Date updatedAt;
}
