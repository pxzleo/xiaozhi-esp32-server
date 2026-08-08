package xiaozhi.modules.device.proactive;

import java.util.Date;

import lombok.Data;

@Data
public class ProactiveEventDedupeEntity {
    private String deviceId;
    private String eventType;
    private String dedupeHash;
    private String lastEventId;
    private Date lastCreatedAt;
}
