package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.service.AutoRestartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
}

