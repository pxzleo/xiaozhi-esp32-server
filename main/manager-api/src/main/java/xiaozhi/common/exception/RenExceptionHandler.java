package xiaozhi.common.exception;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import org.apache.shiro.authz.UnauthorizedException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.common.utils.Result;

/**
 * 异常处理器
 * Copyright (c) 人人开源 All rights reserved.
 * Website: https://www.renren.io
 */
@Slf4j
@AllArgsConstructor
@RestControllerAdvice
public class RenExceptionHandler {

    /**
     * 处理自定义异常
     */
    @ExceptionHandler(RenException.class)
    public Result<Void> handleRenException(RenException ex) {
        Result<Void> result = new Result<>();
        result.error(ex.getCode(), ex.getMsg());

        return result;
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKeyException(DuplicateKeyException ex) {
        Result<Void> result = new Result<>();
        result.error(ErrorCode.DB_RECORD_EXISTS);

        return result;
    }

    @ExceptionHandler(UnauthorizedException.class)
    public Result<Void> handleUnauthorizedException(UnauthorizedException ex) {
        Result<Void> result = new Result<>();
        result.error(ErrorCode.FORBIDDEN);

        return result;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return new Result<Void>().error(404, MessageUtils.getMessage(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        List<ObjectError> allErrors = ex.getBindingResult().getAllErrors();
        String errorMsg = firstSafeValidationMessage(allErrors.stream()
                .filter(Objects::nonNull)
                .map(ObjectError::getDefaultMessage));

        return new Result<Void>().error(ErrorCode.PARAM_VALUE_NULL, errorMsg);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        return new Result<Void>().error(ErrorCode.PARAM_JSON_INVALID, "请求参数格式无效");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException ex) {
        String errorMsg = firstSafeValidationMessage(ex.getConstraintViolations().stream()
                .map(violation -> violation == null ? null : violation.getMessage()));
        return new Result<Void>().error(ErrorCode.PARAM_VALUE_NULL, errorMsg);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public Result<Void> handleHandlerMethodValidationException(HandlerMethodValidationException ex) {
        Stream<String> parameterMessages = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage);
        Stream<String> crossParameterMessages = ex.getCrossParameterValidationResults().stream()
                .map(MessageSourceResolvable::getDefaultMessage);
        String errorMsg = firstSafeValidationMessage(Stream.concat(parameterMessages, crossParameterMessages));
        return new Result<Void>().error(ErrorCode.PARAM_VALUE_NULL, errorMsg);
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception ex) {
        log.error(ex.getMessage(), ex);

        return new Result<Void>().error();
    }

    private String firstSafeValidationMessage(Stream<String> messages) {
        return messages.filter(Objects::nonNull)
                .map(this::sanitizeValidationMessage)
                .filter(message -> !message.isEmpty())
                .findFirst()
                .orElseGet(() -> MessageUtils.getMessage(ErrorCode.PARAM_VALUE_NULL));
    }

    private String sanitizeValidationMessage(String message) {
        StringBuilder safe = new StringBuilder(Math.min(message.length(), 200));
        for (int i = 0; i < message.length() && safe.length() < 200; i++) {
            char value = message.charAt(i);
            safe.append(Character.isISOControl(value) ? ' ' : value);
        }
        return safe.toString().trim();
    }

}
