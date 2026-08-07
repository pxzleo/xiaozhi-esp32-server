package xiaozhi.modules.device.netease;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_device_netease_auth")
public class DeviceNeteaseAuthEntity {
    @TableId
    private String deviceId;
    private String credentialCiphertext;
    private String providerUserId;
    private String nickname;
    private String avatarUrl;
    private String status;
    private String revokeReason;
    private Date createdAt;
    private Date updatedAt;
    private Date revokedAt;
    private Integer version;
}
