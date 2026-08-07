package xiaozhi.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class NeteaseAuthDTO {
    @NotBlank(message = "macAddress不能为空")
    private String macAddress;
}
