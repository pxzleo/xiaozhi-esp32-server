package xiaozhi.modules.mobile;

import java.io.IOException;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;

public final class MobileEventAuditDTOs {
    private MobileEventAuditDTOs() {}

    public record AuditView(
            @JsonProperty("mobile_instance_id") String mobileInstanceId,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("event_id") String eventId,
            String type,
            @JsonProperty("source_package") String sourcePackage,
            String state,
            String summary,
            String category,
            String severity,
            Double confidence,
            @JsonProperty("spoken_summary") String spokenSummary,
            @JsonProperty("reason_code") String reasonCode,
            @JsonProperty("processing_status") String processingStatus,
            @JsonProperty("occurred_at") @JsonSerialize(using = UnixMillisSerializer.class) Long occurredAt,
            @JsonProperty("received_at") @JsonSerialize(using = UnixMillisSerializer.class) Long receivedAt,
            @JsonProperty("processed_at") @JsonSerialize(using = UnixMillisSerializer.class) Long processedAt,
            @JsonProperty("proactive_event_id") String proactiveEventId,
            @JsonProperty("delivery_status") String deliveryStatus) {}

    public static final class UnixMillisSerializer extends StdScalarSerializer<Long> {
        public UnixMillisSerializer() {
            super(Long.class);
        }

        @Override
        public void serialize(Long value, JsonGenerator generator, SerializerProvider provider) throws IOException {
            generator.writeNumber(value);
        }
    }
}
