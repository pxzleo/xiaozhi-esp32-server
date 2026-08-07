package xiaozhi.modules.config.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.config.dto.NeteaseAuthDTO;
import xiaozhi.modules.config.dto.NeteaseInvalidateDTO;
import xiaozhi.modules.device.netease.DeviceNeteaseService;

@RestController
@RequestMapping("/config")
public class NeteaseAuthConfigController {
    private final DeviceNeteaseService service;
    public NeteaseAuthConfigController(DeviceNeteaseService service) { this.service = service; }

    @PostMapping("/netease-auth")
    public Result<DeviceNeteaseService.InternalAuthResponse> auth(@Valid @RequestBody NeteaseAuthDTO dto) {
        return new Result<DeviceNeteaseService.InternalAuthResponse>().ok(service.internalAuth(dto.getMacAddress()));
    }

    @PostMapping("/netease-auth/invalidate")
    public Result<DeviceNeteaseService.InvalidateResponse> invalidate(
            @Valid @RequestBody NeteaseInvalidateDTO dto) {
        return new Result<DeviceNeteaseService.InvalidateResponse>()
                .ok(service.invalidate(dto.getMacAddress(), dto.getCredentialVersion()));
    }
}
