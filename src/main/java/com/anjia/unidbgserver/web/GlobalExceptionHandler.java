package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.service.AutoRestartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private static final String KEY_SUCCESS = "success";
    private static final String KEY_CODE = "code";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_TIMESTAMP = "timestamp";

    private final AutoRestartService autoRestartService;

    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public Object handleAsyncTimeout(AsyncRequestTimeoutException ex, HttpServletRequest request) {
        autoRestartService.recordFailure("ASYNC_TIMEOUT");
        log.warn("异步请求超时", ex);
        return buildError(HttpStatus.GATEWAY_TIMEOUT, 504, "request timeout", request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Object handleMessageNotReadable(HttpMessageNotReadableException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("请求体解析失败: {}", ex.getMessage());
        }
        return buildError(HttpStatus.BAD_REQUEST, 400, "Invalid request body format", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Object handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("不支持的请求方法: {}", ex.getMessage());
        }
        return buildError(HttpStatus.METHOD_NOT_ALLOWED, 405, "Method not allowed: " + ex.getMethod(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Object handleMissingRequestParameter(MissingServletRequestParameterException ex, HttpServletRequest request) {
        String parameterName = ex.getParameterName();
        if (log.isDebugEnabled()) {
            log.debug("缺少请求参数: {}", parameterName);
        }
        return buildError(HttpStatus.BAD_REQUEST, 400, "Missing required parameter: " + parameterName, request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Object handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String parameterName = ex.getName();
        if (log.isDebugEnabled()) {
            log.debug("请求参数类型错误: {} = {}", parameterName, ex.getValue());
        }
        return buildError(HttpStatus.BAD_REQUEST, 400, "Invalid parameter type: " + parameterName, request);
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public Object handleServletBinding(ServletRequestBindingException ex, HttpServletRequest request) {
        if (log.isDebugEnabled()) {
            log.debug("请求绑定失败: {}", ex.getMessage());
        }
        return buildError(HttpStatus.BAD_REQUEST, 400, "Bad request", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Object handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, 400, ex.getMessage() != null ? ex.getMessage() : "bad request", request);
    }

    /**
     * 客户端主动断开连接（浏览器取消请求/网络中断）属于常见噪音，不按服务异常记录。
     * 使用 void 返回避免再次写响应触发二次 Broken pipe 日志。
     */
    @ExceptionHandler(ClientAbortException.class)
    public void handleClientAbort(ClientAbortException ex) {
        if (log.isDebugEnabled()) {
            log.debug("客户端已断开连接: {}", ex.getMessage());
        }
    }

    @ExceptionHandler(Exception.class)
    public Object handleException(Exception ex, HttpServletRequest request) {
        log.error("全局异常捕获: {}", ex.getMessage(), ex);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, 500, "Internal Server Error", request);
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

    private static Object buildError(HttpStatus status, int code, String message, HttpServletRequest request) {
        if (isHtmlRequest(request)) {
            if (request != null) {
                request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status.value());
                request.setAttribute(RequestDispatcher.ERROR_MESSAGE, message);
            }
            ModelAndView modelAndView = new ModelAndView("forward:/error");
            modelAndView.setStatus(status);
            return modelAndView;
        }
        return buildErrorResponse(status, code, message);
    }

    private static boolean isHtmlRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.getHeader("Accept");
        if (accept == null || accept.trim().isEmpty()) {
            return false;
        }
        return accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_HTML_VALUE);
    }
}
