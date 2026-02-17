package com.anjia.unidbgserver.web;

import org.springframework.boot.web.servlet.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.servlet.RequestDispatcher;
import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 统一错误入口：
 * - HTML 请求：转发到静态错误页 /error.html
 * - 非 HTML 请求：返回 JSON 错误响应
 */
@Controller
public class CustomErrorController implements ErrorController {

    @RequestMapping(value = "/error", produces = MediaType.TEXT_HTML_VALUE)
    public String errorHtml() {
        return "forward:/error.html";
    }

    @RequestMapping("/error")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> errorJson(HttpServletRequest request) {
        int status = resolveStatus(request);

        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("code", status);
        body.put("message", HttpStatus.valueOf(status).getReasonPhrase());
        body.put("timestamp", System.currentTimeMillis());

        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    private static int resolveStatus(HttpServletRequest request) {
        if (request == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR.value();
        }
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer) {
            return normalizeStatus((Integer) code);
        }
        if (code != null) {
            try {
                return normalizeStatus(Integer.parseInt(code.toString()));
            } catch (Exception ignored) {
                return HttpStatus.INTERNAL_SERVER_ERROR.value();
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR.value();
    }

    private static int normalizeStatus(int status) {
        return HttpStatus.resolve(status) != null ? status : HttpStatus.INTERNAL_SERVER_ERROR.value();
    }
}
