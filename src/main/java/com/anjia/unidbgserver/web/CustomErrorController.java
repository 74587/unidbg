package com.anjia.unidbgserver.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 统一错误入口：
 * - 统一转发到静态错误页 /error.html
 */
@Controller
public class CustomErrorController {

    @RequestMapping("/error")
    public String errorHtml() {
        return "forward:/error.html";
    }
}
