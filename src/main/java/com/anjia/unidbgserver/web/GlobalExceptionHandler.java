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

    private final AutoRestartService autoRestartService;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        autoRestartService.recordFailure("ASYNC_TIMEOUT");
        log.warn("异步请求超时", ex);

        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 504);
        body.put("message", "request timeout");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.warn("请求体解析失败: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 400);
        body.put("message", "Invalid request body format");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.warn("不支持的请求方法: {}", ex.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 405);
        body.put("message", "Method not allowed: " + ex.getMethod());
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 400);
        body.put("message", ex.getMessage() != null ? ex.getMessage() : "bad request");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        log.error("全局异常捕获: {}", ex.getMessage(), ex);
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 500);
        body.put("message", "Internal Server Error");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }
}
