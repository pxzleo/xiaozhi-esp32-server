package xiaozhi.modules.mobile;

import java.time.Instant;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.mobile.MobileEventAuditDTOs.AuditView;
import xiaozhi.modules.mobile.MobileAlertSettingsDTOs.SettingsUpdate;
import xiaozhi.modules.mobile.MobileAlertSettingsDTOs.SettingsView;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/mobile/events/audit")
@RequiresPermissions("sys:role:normal")
@Validated
public class MobileEventAuditController {
    private final MobileEventAuditService service;

    public MobileEventAuditController(MobileEventAuditService service) {
        this.service = service;
    }

    @GetMapping
    public Result<PageData<AuditView>> audit(
            @RequestParam(value = "mobile_instance_id", required = false)
            @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @RequestParam(required = false)
            @Pattern(regexp = "^(notification\\.state_changed|location\\.transition)$") String type,
            @RequestParam(required = false, name = "processing_status")
            @Pattern(regexp = "^(received|prefiltered|classified|ignored|converted|error)$") String processingStatus,
            @RequestParam(required = false, name = "delivery_status")
            @Pattern(regexp = "^(pending|claimed|delivered|failed|expired|dismissed)$") String deliveryStatus,
            @RequestParam(required = false, name = "from")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false, name = "to")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "1") @Min(1) @Max(1000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return new Result<PageData<AuditView>>().ok(service.audit(SecurityUser.getUserId(), instanceId,
                type, processingStatus, deliveryStatus, from, to, page, limit));
    }

    @GetMapping("/settings")
    public Result<SettingsView> settings(@RequestParam("mobile_instance_id")
            @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId) {
        return new Result<SettingsView>().ok(service.settings(SecurityUser.getUserId(), instanceId));
    }

    @PutMapping("/settings")
    public Result<SettingsView> updateSettings(@RequestParam("mobile_instance_id")
            @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @jakarta.validation.Valid @RequestBody SettingsUpdate request) {
        return new Result<SettingsView>().ok(
                service.updateSettings(SecurityUser.getUserId(), instanceId, request));
    }
}
