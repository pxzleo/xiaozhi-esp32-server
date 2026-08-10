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
    private String reasonCode;
    private Date occurredAt;
    private Date expiresAt;
    private Date createdAt;
    private Date updatedAt;
}
