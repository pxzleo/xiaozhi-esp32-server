package xiaozhi.modules.mobile;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.mobile.MobileAssistantDTOs.AuthorizeRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.AuthorizeResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.BindRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.BindResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MessageClaimResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MessageActionRequest;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MessageActionResponse;
import xiaozhi.modules.mobile.MobileAssistantDTOs.MergeRequest;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@Validated
public class MobileAssistantController {
    private final MobileAssistantService service;
    private final MobileInstanceMergeService mergeService;

    public MobileAssistantController(MobileAssistantService service,
            MobileInstanceMergeService mergeService) {
        this.service = service;
        this.mergeService = mergeService;
    }

    @PostMapping("/mobile/devices/merge")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> merge(@Valid @RequestBody MergeRequest request) {
        mergeService.merge(SecurityUser.getUserId(), request);
        return new Result<>();
    }

    @PostMapping("/mobile/devices/bind")
    @RequiresPermissions("sys:role:normal")
    public Result<BindResponse> bind(@Valid @RequestBody BindRequest request) {
        return new Result<BindResponse>().ok(service.bind(SecurityUser.getUserId(), request));
    }

    @DeleteMapping("/mobile/devices/{instanceId}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> revoke(
            @PathVariable @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @RequestParam("credential_version") int credentialVersion) {
        service.revoke(SecurityUser.getUserId(), instanceId, credentialVersion);
        return new Result<>();
    }

    @PostMapping("/config/mobile/instances/{instanceId}/authorize")
    public Result<AuthorizeResponse> authorize(
            @PathVariable @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @Valid @RequestBody AuthorizeRequest request) {
        return new Result<AuthorizeResponse>().ok(service.authorize(instanceId, request));
    }

    @PostMapping("/config/mobile/instances/{instanceId}/messages/{messageId}/claim")
    public Result<MessageClaimResponse> claimMessage(
            @PathVariable @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String messageId) {
        return new Result<MessageClaimResponse>().ok(service.claimMessage(instanceId, messageId));
    }

    @PostMapping("/config/mobile/instances/{instanceId}/messages/{messageId}/complete")
    public Result<MessageActionResponse> completeMessage(
            @PathVariable @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String messageId,
            @Valid @RequestBody MessageActionRequest request) {
        return new Result<MessageActionResponse>().ok(
                service.completeMessage(instanceId, messageId, request.claimToken()));
    }

    @PostMapping("/config/mobile/instances/{instanceId}/messages/{messageId}/renew")
    public Result<MessageActionResponse> renewMessage(
            @PathVariable @Pattern(regexp = "^mob_[0-9a-f]{32}$") String instanceId,
            @PathVariable @Pattern(regexp = "^[A-Za-z0-9._:-]{1,64}$") String messageId,
            @Valid @RequestBody MessageActionRequest request) {
        return new Result<MessageActionResponse>().ok(
                service.renewMessage(instanceId, messageId, request.claimToken()));
    }
}
