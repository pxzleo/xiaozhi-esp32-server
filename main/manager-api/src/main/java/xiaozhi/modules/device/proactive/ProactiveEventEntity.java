package xiaozhi.modules.device.proactive;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_proactive_event")
public class ProactiveEventEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceId;
    private String macAddress;
    private String eventId;
    private String topic;
    private String priority;
    private String reason;
    private String eventType;
    private String payload;
    private Date createdAt;
    private Date expiresAt;
    private String dedupeKey;
    private Boolean requiresResponse;
    private String deliveryStatus;
    private String outcome;
    private Date deliveredAt;
    private String claimToken;
    private Date claimedAt;
    private Date updatedAt;
}
