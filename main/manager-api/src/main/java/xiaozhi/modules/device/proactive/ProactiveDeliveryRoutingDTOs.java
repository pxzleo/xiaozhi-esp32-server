package xiaozhi.modules.device.proactive;

import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class ProactiveDeliveryRoutingDTOs {
    private ProactiveDeliveryRoutingDTOs() {}

    public abstract static class StrictRequest {
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, JsonNode value) {
            throw new IllegalArgumentException("投递路由包含未知字段: " + key);
        }
    }

    @Data
    public static class RouteUpdate extends StrictRequest {
        @NotNull @Size(max = 100) @JsonProperty("default_device_ids")
        private List<@NotBlank @Size(max = 32) String> defaultDeviceIds;
        @Size(max = 36) @JsonProperty("location_authority_mobile_instance_id")
        private String locationAuthorityMobileInstanceId;
        @NotNull @Valid @Size(max = 100) private List<DeviceRouteUpdate> devices;
        @NotNull @Valid @Size(max = 100) private List<PlaceUpdate> places;
        @NotNull private Integer version;
    }

    @Data
    public static class DeviceRouteUpdate extends StrictRequest {
        @NotBlank @Size(max = 32) @JsonProperty("device_id") private String deviceId;
        @Pattern(regexp = "^place_[0-9a-f]{8,32}$") @JsonProperty("fixed_place_id")
        private String fixedPlaceId;
    }

    @Data
    public static class PlaceUpdate extends StrictRequest {
        @NotBlank @Pattern(regexp = "^place_[0-9a-f]{8,32}$")
        @JsonProperty("place_id") private String placeId;
        @NotBlank @Size(max = 80) @JsonProperty("place_name") private String placeName;
        @NotNull @Size(max = 100) @JsonProperty("device_ids")
        private List<@NotBlank @Size(max = 32) String> deviceIds;
    }

    public record DeviceView(@JsonProperty("device_id") String deviceId,
            @JsonProperty("mac_address") String macAddress, String alias,
            @JsonProperty("terminal_type") String terminalType,
            @JsonProperty("mobile_instance_id") String mobileInstanceId,
            @JsonProperty("fixed_place_id") String fixedPlaceId) {}
    public record PlaceView(@JsonProperty("place_id") String placeId,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("device_ids") List<String> deviceIds) {}
    public record RouteView(@JsonProperty("default_device_ids") List<String> defaultDeviceIds,
            @JsonProperty("location_authority_mobile_instance_id") String locationAuthorityMobileInstanceId,
            List<DeviceView> devices, List<PlaceView> places,
            @JsonProperty("active_place_id") String activePlaceId,
            @JsonProperty("location_observed_at") Date locationObservedAt, int version) {}

    @Data
    public static class LocationAuthorityUpdate extends StrictRequest {
        @Size(max = 36) @JsonProperty("mobile_instance_id") private String mobileInstanceId;
        @NotNull private Integer version;
    }

    @Data
    public static class PlaceDirectoryUpdate extends StrictRequest {
        @NotNull private Integer version;
        @NotNull @Valid @Size(max = 100) private List<PlaceDirectoryItem> places;
    }

    @Data
    public static class PlaceDirectoryItem extends StrictRequest {
        @NotBlank @Pattern(regexp = "^place_[0-9a-f]{8,32}$")
        @JsonProperty("place_id") private String placeId;
        @NotBlank @Size(max = 80) @JsonProperty("place_name") private String placeName;
    }
}
