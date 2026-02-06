package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.service.AutoRestartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import javax.servlet.http.HttpServletRequest;
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

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 400);
        body.put("message", "请求体格式错误");
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        String method = ex.getMethod();
        String path = request != null ? request.getRequestURI() : "";

        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", 405);
        body.put("message", String.format("请求方法不支持: %s %s", method, path));
        body.put("timestamp", System.currentTimeMillis());

        log.warn("请求方法不支持: method={}, path={}", method, path);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
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
