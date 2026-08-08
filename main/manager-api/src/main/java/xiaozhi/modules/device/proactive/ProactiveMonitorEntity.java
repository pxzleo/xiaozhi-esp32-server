package xiaozhi.modules.device.proactive;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("ai_device_proactive_monitor")
public class ProactiveMonitorEntity {
    private String deviceId;
    private String macAddress;
    private String monitorType;
    private Boolean enabled;
    private Integer intervalMinutes;
    private String config;
    private String state;
    private Date lastSuccessAt;
    private Date nextCheckAt;
    private String lastErrorCode;
    private Date lastProbeAt;
    private String leaseOwner;
    private String leaseToken;
    private Date leaseUntil;
    private Integer version;
    private Date createdAt;
    private Date updatedAt;
}
