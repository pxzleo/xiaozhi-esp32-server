package xiaozhi.modules.mobile;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.validation.ConstraintViolationException;
import com.fasterxml.jackson.annotation.JsonProperty;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MobileAssistantController.class)
public class MobileApiExceptionHandler {
    @ExceptionHandler(MobileApiException.class)
    public ResponseEntity<MobileErrorResponse> handleMobileApiException(MobileApiException exception) {
        return response(exception.getStatus(), exception.getErrorCode(), exception.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<MobileErrorResponse> handleInvalidRequest(Exception exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "手机接口请求参数无效");
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
