package xiaozhi.modules.device.proactive;

import java.util.Date;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_proactive_schedule_revision")
public class ProactiveScheduleRevisionEntity {
    @TableId(type = IdType.AUTO) private Long revision;
    private Long userId;
    private String scheduleId;
    private Date createdAt;
}
