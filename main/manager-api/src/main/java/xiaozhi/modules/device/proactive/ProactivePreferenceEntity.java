package xiaozhi.modules.device.proactive;

import java.time.LocalTime;
import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_proactive_preference")
public class ProactivePreferenceEntity {
    @TableId
    private String deviceId;
    private String macAddress;
    private String mode;
    private Integer dailyLimit;
    private LocalTime quietStart;
    private LocalTime quietEnd;
    private String allowedTopics;
    private String blockedTopics;
    private String previousMode;
    private Integer previousDailyLimit;
    private Date silentUntil;
    private Integer version;
    private Date createdAt;
    private Date updatedAt;
}
