package xiaozhi.modules.security.device;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.apache.shiro.web.filter.AccessControlFilter;
import org.springframework.web.bind.annotation.RequestMethod;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import xiaozhi.common.constant.Constant;
import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.utils.JsonUtils;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.sys.service.SysParamsService;

public class DeviceTokenFilter extends AccessControlFilter {
    static final long MAX_AGE_SECONDS = 30L * 24 * 60 * 60;
    private final SysParamsService sysParamsService;

    public DeviceTokenFilter(SysParamsService sysParamsService) {
        this.sysParamsService = sysParamsService;
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        return RequestMethod.OPTIONS.name().equals(((HttpServletRequest) request).getMethod());
    }

    @Override
    protected boolean onAccessDenied(ServletRequest request, ServletResponse response) {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String deviceId = httpRequest.getHeader("Device-Id");
        String clientId = httpRequest.getHeader("Client-Id");
        String authorization = httpRequest.getHeader(Constant.AUTHORIZATION);
        String token = StringUtils.startsWith(authorization, "Bearer ") ? authorization.substring(7) : null;
        String secret = sysParamsService.getValue(Constant.SERVER_SECRET, true);
        if (!verify(token, clientId, deviceId, secret, Instant.now().getEpochSecond())) {
            sendUnauthorized((HttpServletResponse) response);
            return false;
        }
        return true;
    }

    static boolean verify(String token, String clientId, String deviceId, String secret, long now) {
        if (StringUtils.isAnyBlank(token, clientId, deviceId, secret) || "null".equalsIgnoreCase(secret)) {
            return false;
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(parts[1]);
            if (timestamp > now || now - timestamp > MAX_AGE_SECONDS) {
                return false;
            }
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signed = hmac.doFinal((clientId + "|" + deviceId + "|" + timestamp)
                    .getBytes(StandardCharsets.UTF_8));
            String expected = Base64.getUrlEncoder().withoutPadding().encodeToString(signed);
            return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    parts[0].getBytes(StandardCharsets.US_ASCII));
        } catch (Exception exception) {
            return false;
        }
    }

    private void sendUnauthorized(HttpServletResponse response) {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=utf-8");
        try {
            response.getWriter().print(JsonUtils.toJsonString(
                    new Result<Void>().error(ErrorCode.UNAUTHORIZED, "设备认证失败")));
        } catch (IOException exception) {
            throw new IllegalStateException("设备认证失败响应写入失败", exception);
        }
    }
}
