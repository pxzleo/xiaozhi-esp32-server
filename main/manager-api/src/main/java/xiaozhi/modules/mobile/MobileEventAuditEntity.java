package xiaozhi.modules.mobile;

import java.util.Date;
import lombok.Data;

@Data
public class MobileEventAuditEntity {
    private Long id;
    private String mobileInstanceId;
    private String eventId;
    private String status;
    private String reasonCode;
    private Date createdAt;
}
