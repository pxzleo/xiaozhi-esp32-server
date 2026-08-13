package xiaozhi.modules.mobile;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import xiaozhi.modules.mobile.MobileProactiveDTOs.ClaimRequest;
import xiaozhi.modules.mobile.MobileProactiveDTOs.ClaimResponse;
import xiaozhi.modules.mobile.MobileProactiveDTOs.CompleteRequest;
import xiaozhi.modules.mobile.MobileProactiveDTOs.CompleteResponse;
import xiaozhi.modules.mobile.MobileProactiveDTOs.PendingResponse;
import xiaozhi.modules.mobile.MobileProactiveDTOs.QuietHoursRequest;
import xiaozhi.modules.mobile.MobileProactiveDTOs.QuietHoursResponse;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.LocationAuthorityUpdate;
import xiaozhi.modules.device.proactive.ProactiveDeliveryRoutingDTOs.RouteView;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.Action;
import xiaozhi.modules.device.proactive.ProactiveScheduleDTOs.View;

@RestController
@Validated
public class MobileProactiveController {
    private final MobileProactiveService service;
    public MobileProactiveController(MobileProactiveService service) { this.service = service; }

    @GetMapping("/mobile/proactive/pending")
    public PendingResponse pending(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion) {
        return service.pending(auth(authorization, instanceId, installationId, credentialVersion, protocolVersion));
    }

    @GetMapping("/mobile/proactive/quiet-hours")
    public QuietHoursResponse quietHours(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion) {
        return service.quietHours(auth(authorization, instanceId, installationId,
                credentialVersion, protocolVersion));
    }

    @PutMapping("/mobile/proactive/quiet-hours")
    public QuietHoursResponse updateQuietHours(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @Valid @RequestBody QuietHoursRequest request) {
        return service.updateQuietHours(auth(authorization, instanceId, installationId,
                credentialVersion, protocolVersion), request);
    }

    @GetMapping("/mobile/proactive/delivery-routing")
    public RouteView deliveryRouting(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion) {
        return service.deliveryRouting(auth(authorization, instanceId, installationId,
                credentialVersion, protocolVersion));
    }

    @PutMapping("/mobile/proactive/location-authority")
    public RouteView updateLocationAuthority(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @Valid @RequestBody LocationAuthorityUpdate request) {
        return service.updateLocationAuthority(auth(authorization, instanceId, installationId,
                credentialVersion, protocolVersion), request);
    }

    @GetMapping("/mobile/proactive/schedules")
    public java.util.List<View> activeSchedules(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion) {
        return service.activeSchedules(auth(authorization,instanceId,installationId,
                credentialVersion,protocolVersion));
    }

    @PostMapping("/mobile/proactive/schedules/{id}:action")
    public View scheduleAction(@PathVariable @Pattern(regexp="^[0-9a-f-]{36}$") String id,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @Valid @RequestBody Action request) {
        return service.scheduleAction(auth(authorization,instanceId,installationId,
                credentialVersion,protocolVersion),id,request);
    }

    @PostMapping("/mobile/proactive/{eventId}:claim")
    public ClaimResponse claim(@PathVariable @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9:_-]+") String eventId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @Valid @RequestBody ClaimRequest request) {
        return service.claim(auth(authorization, instanceId, installationId, credentialVersion, protocolVersion),
                eventId, request);
    }

    @PostMapping("/mobile/proactive/{eventId}:complete")
    public CompleteResponse complete(@PathVariable @Size(max = 64)
            @Pattern(regexp = "[A-Za-z0-9:_-]+") String eventId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @Valid @RequestBody CompleteRequest request) {
        return service.complete(auth(authorization, instanceId, installationId, credentialVersion, protocolVersion),
                eventId, request);
    }

    private MobileEventService.MobileAuth auth(String authorization, String instanceId,
            String installationId, int credentialVersion, int protocolVersion) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new MobileApiException(HttpStatus.UNAUTHORIZED, "MOBILE_CREDENTIAL_INVALID",
                    "手机凭据无效或已撤销");
        }
        return new MobileEventService.MobileAuth(instanceId, installationId, credentialVersion,
                protocolVersion, authorization.substring(7));
    }
}
