package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.service.FQEncryptServiceWorker;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(path = "/api/fq-signature", produces = MediaType.APPLICATION_JSON_VALUE)
public class FQEncryptController {

    @Resource(name = "fqEncryptWorker")
    private FQEncryptServiceWorker fqSignatureServiceWorker;

    /**
     * 生成FQ应用的签名headers
     * @param request 包含 url 和 headers 的请求体
     * @return 包含各种签名header的结果
     */
    @SneakyThrows
    @RequestMapping(value = "generateSignature", method = {RequestMethod.POST})
    public Map<String, String> generateSignature(@RequestBody Map<String, String> request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String url = request.get("url");
        String headers = request.get("headers");

        // 检查必需的参数
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL参数不能为空");
        }

        // headers可以为空，默认为空字符串
        if (headers == null) {
            headers = "";
        }

        if (log.isDebugEnabled()) {
            log.debug("接收到FQ签名请求 - URL: {}", url);
            log.debug("接收到FQ签名请求 - Headers: {}", headers);
        }

        // 调用服务生成签名
        Map<String, String> result = fqSignatureServiceWorker.generateSignatureHeaders(url, headers).get();

        if (log.isDebugEnabled()) {
            log.debug("FQ签名生成完成，结果数量: {}", result.size());
        }

        return result;
    }

    /**
     * 生成FQ应用的签名headers (支持Map格式的headers)
     * @param request 包含 url 和 headerMap 的请求体
     * @return 包含各种签名header的结果
     */
    @SneakyThrows
    @RequestMapping(value = "generateSignatureWithMap", method = {RequestMethod.POST})
    public Map<String, String> generateSignatureWithMap(@RequestBody Map<String, Object> request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        Object urlRaw = request.get("url");
        String url = urlRaw != null ? String.valueOf(urlRaw) : null;
        Map<String, String> headerMap = coerceHeaderMap(request.get("headerMap"));

        // 检查必需的参数
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL参数不能为空");
        }

        if (log.isDebugEnabled()) {
            log.debug("接收到FQ签名请求(Map格式) - URL: {}", url);
            log.debug("接收到FQ签名请求(Map格式) - HeaderMap: {}", headerMap);
        }

        // 调用服务生成签名
        Map<String, String> result = fqSignatureServiceWorker.generateSignatureHeaders(url, headerMap).get();

        if (log.isDebugEnabled()) {
            log.debug("FQ签名生成完成，结果数量: {}", result.size());
        }

        return result;
    }

    /**
     * 简化版签名生成，只需要URL
     * @param request 包含 url 的请求体
     * @return 包含各种签名header的结果
     */
    @SneakyThrows
    @RequestMapping(value = "generateSignatureSimple", method = {RequestMethod.POST})
    public Map<String, String> generateSignatureSimple(@RequestBody Map<String, String> request) {
        if (request == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String url = request.get("url");

        // 检查必需的参数
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("URL参数不能为空");
        }

        if (log.isDebugEnabled()) {
            log.debug("接收到FQ简化签名请求 - URL: {}", url);
        }

        // 调用服务生成签名
        Map<String, String> result = fqSignatureServiceWorker.generateSignatureHeaders(url, "").get();

        if (log.isDebugEnabled()) {
            log.debug("FQ简化签名生成完成，结果数量: {}", result.size());
        }

        return result;
    }

    private Map<String, String> coerceHeaderMap(Object rawHeaderMap) {
        if (rawHeaderMap == null) {
            return Collections.emptyMap();
        }
        if (!(rawHeaderMap instanceof Map)) {
            throw new IllegalArgumentException("headerMap参数格式错误，必须是对象");
        }

        Map<?, ?> source = (Map<?, ?>) rawHeaderMap;
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = String.valueOf(entry.getKey()).trim();
            if (key.isEmpty()) {
                continue;
            }
            Object value = entry.getValue();
            result.put(key, value == null ? "" : String.valueOf(value));
        }
        return result;
    }

}
