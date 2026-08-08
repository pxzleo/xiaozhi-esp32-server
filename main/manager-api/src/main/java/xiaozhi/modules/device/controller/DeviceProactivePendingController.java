package xiaozhi.modules.device.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

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

    public DeviceProactivePendingController(ProactiveMonitorService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public Result<PendingEnvelope> pending(
            @RequestHeader("Device-Id") @NotBlank @Size(max = 32) String deviceId) {
        return new Result<PendingEnvelope>().ok(service.pending(deviceId));
    }
}
