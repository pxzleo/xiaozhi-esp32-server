package xiaozhi.modules.device.netease;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_netease_session")
public class DeviceNeteaseSessionEntity {
    @TableId
    private String sessionId;
    private String deviceId;
    private String qrKeyCiphertext;
    private String qrContent;
    private String status;
    private String failureReason;
    private Date expiresAt;
    private String activeSlot;
    private Date createdAt;
    private Date updatedAt;
}
