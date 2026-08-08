package xiaozhi.common.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

class RenExceptionHandlerTest {
    private final RenExceptionHandler handler = new RenExceptionHandler();

    @Test
    void unknownJsonFieldReturnsSafeJsonErrorWithoutEchoingBody() {
        var exception = new HttpMessageNotReadableException(
                "hidden_prompt from body: highly-sensitive-input");

        var result = handler.handleHttpMessageNotReadableException(exception);

        assertEquals(ErrorCode.PARAM_JSON_INVALID, result.getCode());
        assertEquals("请求参数格式无效", result.getMsg());
        assertFalse(result.getMsg().contains("highly-sensitive-input"));
    }

    @Test
    void illegalEnumReturnsSafeJsonErrorInsteadOfGeneric500() {
        var exception = new HttpMessageNotReadableException(
                "Cannot deserialize enum value SECRET_ENUM_INPUT");

        var result = handler.handleHttpMessageNotReadableException(exception);

        assertEquals(ErrorCode.PARAM_JSON_INVALID, result.getCode());
        assertFalse(result.getCode() == ErrorCode.INTERNAL_SERVER_ERROR);
        assertFalse(result.getMsg().contains("SECRET_ENUM_INPUT"));
    }

    @Test
    void pathPageConstraintReturnsFirstSafeViolationMessage() {
        @SuppressWarnings("unchecked")
        ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
        when(violation.getMessage()).thenReturn("page不能大于1000");
        var exception = new ConstraintViolationException(
                "request contained secret-page-value", Set.of(violation));

        var result = handler.handleConstraintViolationException(exception);

        assertEquals(ErrorCode.PARAM_VALUE_NULL, result.getCode());
        assertEquals("page不能大于1000", result.getMsg());
        assertFalse(result.getMsg().contains("secret-page-value"));
    }

    @Test
    void springMethodValidationReturnsResolvableMessageInsteadOfGeneric500() {
        HandlerMethodValidationException exception = mock(HandlerMethodValidationException.class);
        ParameterValidationResult validation = mock(ParameterValidationResult.class);
        MessageSourceResolvable error = mock(MessageSourceResolvable.class);
        when(error.getDefaultMessage()).thenReturn("limit不能大于100");
        when(validation.getResolvableErrors()).thenReturn(List.of(error));
        when(exception.getParameterValidationResults()).thenReturn(List.of(validation));
        when(exception.getCrossParameterValidationResults()).thenReturn(List.of());

        var result = handler.handleHandlerMethodValidationException(exception);

        assertEquals(ErrorCode.PARAM_VALUE_NULL, result.getCode());
        assertEquals("limit不能大于100", result.getMsg());
        assertFalse(result.getCode() == ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
