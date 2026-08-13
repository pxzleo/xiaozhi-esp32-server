package xiaozhi.modules.device.proactive;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_proactive_schedule")
public class ProactiveScheduleEntity {
    @TableId private String id;
    private Long userId;
    private String sourceDeviceId;
    private String sourceScheduleId;
    private String kind;
    private String label;
    private String recurrence;
    private String weekdays;
    private String sections;
    private String location;
    private Date scheduledAt;
    private String state;
    private Date lastTriggeredAt;
    private Date snoozedUntil;
    private Integer version;
    private Date createdAt;
    private Date updatedAt;
}
