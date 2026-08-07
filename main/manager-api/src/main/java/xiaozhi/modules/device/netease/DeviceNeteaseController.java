package xiaozhi.modules.device.netease;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.utils.Result;

@RestController
@RequestMapping("/device/netease")
public class DeviceNeteaseController {
    private final DeviceNeteaseService service;
    private final String publicBaseUrl;

    public DeviceNeteaseController(DeviceNeteaseService service,
            @Value("${netease-music.public-base-url:}") String publicBaseUrl) {
        this.service = service;
        this.publicBaseUrl = StringUtils.removeEnd(StringUtils.trimToEmpty(publicBaseUrl), "/");
    }

    public record LoginStatusResponse(String status) {}
    public record LoginSessionResponse(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("qr_image_url") String qrImageUrl,
            @JsonProperty("expires_in_ms") int expiresInMs,
            @JsonProperty("poll_interval_ms") int pollIntervalMs) {}
    public record AuthorizationStatusResponse(String status) {}
    public record LogoutStatusResponse(String status) {}

    @GetMapping("/status")
    public Result<LoginStatusResponse> status(@RequestHeader("Device-Id") String deviceId) {
        NeteaseLoginStatus status = service.status(deviceId).status();
        return new Result<LoginStatusResponse>().ok(new LoginStatusResponse(
                status == NeteaseLoginStatus.LOGGED_IN ? "logged_in" : "logged_out"));
    }

    @PostMapping("/sessions")
    public Result<LoginSessionResponse> create(@RequestHeader("Device-Id") String deviceId) {
        URI baseUri = requirePublicBaseUri();
        DeviceNeteaseService.SessionResponse session = service.createSession(deviceId);
        int expiresInMs = Math.toIntExact(Math.max(1,
                Duration.between(Instant.now(), session.expiresAt().toInstant()).toMillis()));
        String qrImageUrl = baseUri.resolve("device/netease/sessions/" + session.sessionId() + "/qr-image")
                .toString();
        return new Result<LoginSessionResponse>().ok(new LoginSessionResponse(
                session.sessionId(), qrImageUrl, expiresInMs, session.pollIntervalSeconds() * 1000));
    }

    @GetMapping("/sessions/{sessionId}")
    public Result<AuthorizationStatusResponse> poll(@RequestHeader("Device-Id") String deviceId,
            @PathVariable String sessionId) {
        DeviceNeteaseService.StatusResponse response = service.poll(deviceId, sessionId);
        return new Result<AuthorizationStatusResponse>().ok(
                new AuthorizationStatusResponse(toAuthorizationStatus(response)));
    }

    @GetMapping("/sessions/{sessionId}/qr-image")
    public ResponseEntity<byte[]> qrImage(@RequestHeader("Device-Id") String deviceId,
            @PathVariable String sessionId) {
        DeviceNeteaseService.QrImage image = service.qrImage(deviceId, sessionId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(image.contentType()))
                .contentLength(image.bytes().length)
                .body(image.bytes());
    }

    @PostMapping("/logout")
    public Result<LogoutStatusResponse> logout(@RequestHeader("Device-Id") String deviceId) {
        DeviceNeteaseService.LogoutResponse response = service.logout(deviceId);
        String status = "ALREADY_LOGGED_OUT".equals(response.outcome())
                ? "already_logged_out" : "logged_out";
        return new Result<LogoutStatusResponse>().ok(new LogoutStatusResponse(status));
    }

    private URI requirePublicBaseUri() {
        try {
            URI uri = URI.create(publicBaseUrl + "/");
            boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            if (!supportedScheme || StringUtils.isBlank(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("invalid public base url");
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new RenException("网易云二维码访问地址未配置或格式无效", exception);
        }
    }

    private String toAuthorizationStatus(DeviceNeteaseService.StatusResponse response) {
        return switch (response.status()) {
            case WAITING_SCAN, SCANNED -> "pending";
            case LOGGED_IN -> "authorized";
            case EXPIRED -> "expired";
            case FAILED -> switch (StringUtils.defaultString(response.reason())) {
                case "USER_LOGOUT", "ADMIN_REVOKED", "DEVICE_REMOVED" -> "cancelled";
                default -> "failed";
            };
            case NOT_LOGGED_IN -> "cancelled";
        };
    }
}
