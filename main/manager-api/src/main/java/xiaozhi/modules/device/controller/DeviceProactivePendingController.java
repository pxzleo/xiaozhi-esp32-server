package xiaozhi.modules.device.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import com.fasterxml.jackson.core.JsonProcessingException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.proactive.ProactiveDTOs.PendingEnvelope;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;

@RestController
@RequestMapping("/device/proactive")
@Validated
public class DeviceProactivePendingController {
    private final ProactiveMonitorService service;
    private final MappingJackson2HttpMessageConverter jsonConverter;

    public DeviceProactivePendingController(ProactiveMonitorService service,
            MappingJackson2HttpMessageConverter jsonConverter) {
        this.service = service;
        this.jsonConverter = jsonConverter;
    }

    @GetMapping("/pending")
    public ResponseEntity<byte[]> pending(
            @RequestHeader("Device-Id") @NotBlank @Size(max = 32) String deviceId)
            throws JsonProcessingException {
        Result<PendingEnvelope> result = new Result<PendingEnvelope>().ok(service.pending(deviceId));
        byte[] body = jsonConverter.getObjectMapper().writeValueAsBytes(result);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(body.length)
                .body(body);
    }
}
