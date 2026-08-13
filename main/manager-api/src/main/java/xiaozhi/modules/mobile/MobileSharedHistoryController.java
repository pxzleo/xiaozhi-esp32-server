package xiaozhi.modules.mobile;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import xiaozhi.modules.mobile.MobileSharedHistoryDTOs.Response;

@RestController
@Validated
public class MobileSharedHistoryController {
    private final MobileSharedHistoryService service;
    public MobileSharedHistoryController(MobileSharedHistoryService service) { this.service = service; }

    @GetMapping("/mobile/chat-history")
    public Response list(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader("Mobile-Instance-Id") String instanceId,
            @RequestHeader("Client-Id") String installationId,
            @RequestHeader("Mobile-Credential-Version") int credentialVersion,
            @RequestHeader("Mobile-Protocol-Version") int protocolVersion,
            @RequestParam(name = "before_id", required = false) Long beforeId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        if (authorization == null || !authorization.startsWith("Bearer ")
                || authorization.length() <= 7) {
            throw new MobileApiException(HttpStatus.UNAUTHORIZED,
                    "MOBILE_CREDENTIAL_INVALID", "手机凭据无效或已撤销");
        }
        return service.list(new MobileEventService.MobileAuth(instanceId, installationId,
                credentialVersion, protocolVersion, authorization.substring(7)), beforeId, limit);
    }
}
