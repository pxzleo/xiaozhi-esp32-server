package xiaozhi.modules.mobile;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class MobileAssistantDTOs {
    private MobileAssistantDTOs() {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BindRequest(
            int version,
            @JsonProperty("installation_id")
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
            String installationId,
            @JsonProperty("stable_device_key")
            @Pattern(regexp = "^[0-9a-f]{64}$") String stableDeviceKey,
            @NotBlank @Pattern(regexp = "^android$") String platform,
            @JsonProperty("app_version") @NotBlank @Pattern(regexp = "^[A-Za-z0-9._+-]{1,20}$") String appVersion,
            @JsonProperty("agent_id") @NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{1,32}$") String agentId,
            @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 32) String> capabilities) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机绑定请求包含未知字段");
        }
    }

    public record BindResponse(
            @JsonProperty("version") int version,
            @JsonProperty("mobile_instance_id") String mobileInstanceId,
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("credential_version") int credentialVersion,
            @JsonProperty("websocket_url") String websocketUrl,
            @JsonProperty("websocket_path") String websocketPath) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MergeRequest(
            @JsonProperty("canonical_device_id")
            @NotBlank @Pattern(regexp = "^[0-9a-f]{32}$") String canonicalDeviceId,
            @JsonProperty("duplicate_device_ids")
            @NotEmpty @Size(max = 20) List<@Pattern(regexp = "^[0-9a-f]{32}$") String> duplicateDeviceIds) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机合并请求包含未知字段");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthorizeRequest(
            int version,
            @JsonProperty("credential_version") int credentialVersion,
            @JsonProperty("installation_id") @NotBlank @Size(max = 36) String installationId,
            @NotBlank @Size(max = 128) String token,
            @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 32) String> capabilities) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机鉴权请求包含未知字段");
        }
    }

    public record AuthorizeResponse(
            boolean authorized,
            @JsonProperty("mobile_instance_id") String mobileInstanceId,
            @JsonProperty("agent_id") String agentId,
            @JsonProperty("credential_version") Integer credentialVersion) {
        static AuthorizeResponse denied() {
            return new AuthorizeResponse(false, null, null, null);
        }
    }

    public record MessageClaimResponse(String status, @JsonProperty("claim_token") String claimToken) {
    }

    public record MessageActionResponse(boolean completed) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MessageActionRequest(
            @JsonProperty("claim_token") @NotBlank @Pattern(regexp = "^[0-9a-f]{32}$") String claimToken) {
        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("手机消息确认请求包含未知字段");
        }
    }
}
