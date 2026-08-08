package xiaozhi.modules.device.proactive;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_proactive_habit")
public class ProactiveHabitEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String deviceId;
    private String macAddress;
    private String habitType;
    private String habitKey;
    private Integer evidenceCount;
    private Date firstSeenAt;
    private Date lastSeenAt;
    private Boolean suggested;
    private Boolean accepted;
    private Boolean dismissed;
    private String payload;
    private Date createdAt;
    private Date updatedAt;
}
