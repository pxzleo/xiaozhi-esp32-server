package xiaozhi.modules.config.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class NeteaseInvalidateDTO {
    @NotBlank(message = "macAddress不能为空")
    private String macAddress;
    @NotNull(message = "credentialVersion不能为空")
    private Integer credentialVersion;
}
