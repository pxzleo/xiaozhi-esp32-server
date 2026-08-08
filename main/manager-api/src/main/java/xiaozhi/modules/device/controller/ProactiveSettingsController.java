package xiaozhi.modules.device.controller;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ExternalMonitoringUpdate;
import xiaozhi.modules.device.proactive.ProactiveDTOs.ExternalMonitoringView;
import xiaozhi.modules.device.proactive.ProactiveMonitorService;

@RestController
@RequestMapping("/proactive/settings")
@RequiresPermissions("sys:role:superAdmin")
public class ProactiveSettingsController {
    private final ProactiveMonitorService service;

    public ProactiveSettingsController(ProactiveMonitorService service) {
        this.service = service;
    }

    @GetMapping("/external-monitoring")
    public Result<ExternalMonitoringView> externalMonitoring() {
        return new Result<ExternalMonitoringView>().ok(service.externalMonitoringSetting());
    }

    @PutMapping("/external-monitoring")
    public Result<ExternalMonitoringView> saveExternalMonitoring(
            @Valid @RequestBody ExternalMonitoringUpdate request) {
        return new Result<ExternalMonitoringView>().ok(
                service.saveExternalMonitoringSetting(request.getEnabled()));
    }
}
