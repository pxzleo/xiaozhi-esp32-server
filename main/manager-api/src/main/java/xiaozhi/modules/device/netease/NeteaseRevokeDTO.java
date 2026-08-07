package xiaozhi.modules.device.netease;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NeteaseRevokeDTO {
    @NotBlank(message = "撤销原因不能为空")
    private String reason;
}
