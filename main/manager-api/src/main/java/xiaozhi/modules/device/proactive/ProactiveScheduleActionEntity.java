package xiaozhi.modules.device.proactive;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_proactive_schedule_action")
public class ProactiveScheduleActionEntity {
    @TableId(type = IdType.AUTO) private Long revision;
    private String actionId;
    private Long userId;
    private String scheduleId;
    private String sourceDeviceId;
    private String sourceScheduleId;
    private Integer scheduleVersion;
    private String action;
    private Date snoozedUntil;
    private Date nextTriggerAt;
    private Date createdAt;
}
