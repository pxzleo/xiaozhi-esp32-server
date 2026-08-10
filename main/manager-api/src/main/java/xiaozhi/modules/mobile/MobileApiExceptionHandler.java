package xiaozhi.modules.mobile;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MissingRequestHeaderException;

import jakarta.validation.ConstraintViolationException;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {MobileAssistantController.class, MobileEventController.class,
        MobileProactiveController.class})
public class MobileApiExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MobileApiExceptionHandler.class);
    @ExceptionHandler(MobileApiException.class)
    public ResponseEntity<MobileErrorResponse> handleMobileApiException(MobileApiException exception) {
        return response(exception.getStatus(), exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<MobileErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "手机接口请求参数无效");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<MobileErrorResponse> handleMissingHeader(MissingRequestHeaderException exception) {
        if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(exception.getHeaderName())) {
            return response(HttpStatus.UNAUTHORIZED, "MOBILE_CREDENTIAL_MISSING", "缺少手机访问凭据");
        }
        return response(HttpStatus.BAD_REQUEST, "MOBILE_PROTOCOL_HEADER_MISSING", "缺少手机协议请求头");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<MobileErrorResponse> handleUnexpected(Exception exception) {
        LOGGER.error("手机接口处理失败: {}", exception.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "MOBILE_INTERNAL_ERROR", "手机服务暂时不可用");
    }

    private ResponseEntity<MobileErrorResponse> response(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new MobileErrorResponse(1, code, message));
    }

    public record MobileErrorResponse(
            int version,
            @JsonProperty("error_code") String errorCode,
            String message) {
    }
}
