package xiaozhi.modules.mobile;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MobileAlertSettingsDTOs {
    private MobileAlertSettingsDTOs() {}

    public record SettingsView(@JsonProperty("mobile_instance_id") String mobileInstanceId,
            String sensitivity, List<String> categories) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SettingsUpdate(
            @NotNull @Pattern(regexp = "^(conservative|balanced|timely)$") String sensitivity,
            @NotEmpty @Size(max = 6) List<@NotNull @Pattern(
                    regexp = "^(security|call|parcel|appointment|message|other)$") String> categories) {
        @JsonAnySetter public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机提醒设置包含未知字段");
        }
    }
}
