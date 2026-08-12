package xiaozhi.modules.mobile;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_mobile_instance")
public class MobileInstanceEntity {
    @TableId
    private String mobileInstanceId;
    private String deviceId;
    private Long userId;
    private String installationId;
    private String stableDeviceKey;
    private String canonicalInstanceId;
    private String agentId;
    private String platform;
    private String appVersion;
    private String capabilities;
    private String alertSensitivity;
    private String alertCategories;
    private String credentialHash;
    private Integer credentialVersion;
    private Date revokedAt;
    private Date lastConnectedAt;
    private Date createdAt;
    private Date updatedAt;
}
