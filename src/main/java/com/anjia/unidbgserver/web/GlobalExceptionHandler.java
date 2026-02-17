package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.service.AutoRestartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_TIMESTAMP = "timestamp";

    private final AutoRestartService autoRestartService;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        autoRestartService.recordFailure("ASYNC_TIMEOUT");
        log.warn("异步请求超时", ex);
        return buildErrorResponse(HttpStatus.GATEWAY_TIMEOUT, 504, "request timeout");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, 400, "Invalid request body format");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("不支持的请求方法: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.METHOD_NOT_ALLOWED, 405, "Method not allowed: " + ex.getMethod());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        return buildErrorResponse(HttpStatus.BAD_REQUEST, 400, ex.getMessage() != null ? ex.getMessage() : "bad request");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("全局异常捕获: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, 500, "Internal Server Error");
    }

    private static ResponseEntity<Map<String, Object>> buildErrorResponse(HttpStatus status, int code, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put(KEY_SUCCESS, false);
        body.put(KEY_CODE, code);
        body.put(KEY_MESSAGE, message);
        body.put(KEY_TIMESTAMP, System.currentTimeMillis());
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
