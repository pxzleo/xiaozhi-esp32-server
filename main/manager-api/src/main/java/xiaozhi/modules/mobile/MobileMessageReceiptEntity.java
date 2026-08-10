package xiaozhi.modules.mobile;

import java.util.Date;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("ai_mobile_message_receipt")
public class MobileMessageReceiptEntity {
    private String mobileInstanceId;
    private String messageId;
    private String status;
    private String claimToken;
    private Date leaseExpiresAt;
    private Date createdAt;
    private Date updatedAt;
}
