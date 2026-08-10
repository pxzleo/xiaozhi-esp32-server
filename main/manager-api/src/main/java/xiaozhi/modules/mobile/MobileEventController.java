package xiaozhi.modules.mobile;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import xiaozhi.modules.mobile.MobileEventDTOs.BatchRequest;
import xiaozhi.modules.mobile.MobileEventDTOs.BatchResponse;
import xiaozhi.modules.mobile.MobileEventDTOs.ConfigResponse;
import xiaozhi.modules.mobile.MobileEventDTOs.StatusResponse;

@RestController
@Validated
public class MobileEventController {
    private final MobileEventService service;
    public MobileEventController(MobileEventService service) { this.service = service; }

    @GetMapping("/mobile/config")
    public ResponseEntity<ConfigResponse> config(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        ConfigResponse body = service.config(auth(authorization, instanceId, installationId, credentialVersion, protocolVersion));
        String etag = service.configEtag(body);
        if (etag.equals(ifNoneMatch)) return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        return ResponseEntity.ok().eTag(etag).body(body);
    }

    @PostMapping("/mobile/events:batch")
    public BatchResponse batch(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @Valid @RequestBody BatchRequest request) {
        return service.accept(auth(authorization, instanceId, installationId, credentialVersion, protocolVersion), request);
    }

    @GetMapping("/mobile/events/status")
    public StatusResponse status(
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        return service.status(auth(authorization, instanceId, installationId, credentialVersion, protocolVersion), limit);
    }

    private MobileEventService.MobileAuth auth(String authorization, String instanceId,
            String installationId, int credentialVersion, int protocolVersion) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new MobileApiException(HttpStatus.UNAUTHORIZED, "MOBILE_CREDENTIAL_INVALID", "手机凭据无效或已撤销");
        }
        return new MobileEventService.MobileAuth(instanceId, installationId, credentialVersion, protocolVersion,
                authorization.substring(7));
    }
}
