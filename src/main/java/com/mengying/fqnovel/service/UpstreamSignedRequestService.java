package com.mengying.fqnovel.service;

import com.mengying.fqnovel.utils.GzipUtils;
import com.mengying.fqnovel.utils.Texts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 统一处理上游请求流程（GET/POST）：签名、请求、解压、JSON 解析。
 */
@Service
public class UpstreamSignedRequestService {

    public static final String REASON_ILLEGAL_ACCESS = "ILLEGAL_ACCESS";
    public static final String REASON_UPSTREAM_EMPTY = "UPSTREAM_EMPTY";
    public static final String REASON_UPSTREAM_GZIP = "UPSTREAM_GZIP";
    public static final String REASON_UPSTREAM_NON_JSON = "UPSTREAM_NON_JSON";
    public static final String REASON_SIGNER_FAIL = "SIGNER_FAIL";

    private static final String EX_EMPTY_UPSTREAM_RESPONSE = "Empty upstream response";
    private static final String EX_GZIP_NOT_IN_FORMAT = "Not in GZIP format";
    private static final String EX_JACKSON_EMPTY_CONTENT = "No content to map due to end-of-input";
    private static final String EX_SIGNER_FAIL = "签名生成失败";

    private final FQEncryptServiceWorker fqEncryptServiceWorker;
    private final UpstreamRateLimiter upstreamRateLimiter;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public UpstreamSignedRequestService(
        FQEncryptServiceWorker fqEncryptServiceWorker,
        UpstreamRateLimiter upstreamRateLimiter,
        RestTemplate restTemplate,
        ObjectMapper objectMapper
    ) {
        this.fqEncryptServiceWorker = fqEncryptServiceWorker;
        this.upstreamRateLimiter = upstreamRateLimiter;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public UpstreamJsonResult executeSignedJsonGet(String fullUrl, Map<String, String> headers) throws Exception {
        return toJsonResult(executeSignedRawGet(fullUrl, headers));
    }

    /**
     * 执行签名 GET 并在签名失败时记录日志。
     * 从 FQSearchService / FQDirectoryService 中提取共用。
     */
    public UpstreamJsonResult executeSignedJsonGetOrLogFailure(
        String fullUrl,
        Map<String, String> headers,
        String failureScene,
        Logger callerLog
    ) throws Exception {
        UpstreamJsonResult upstream = executeSignedJsonGet(fullUrl, headers);
        if (upstream == null) {
            callerLog.error("签名生成失败，终止{} - url: {}", failureScene, fullUrl);
        }
        return upstream;
    }

    /**
     * 提取上游非零响应码，0 视为成功，返回 null。
     */
    public static Integer nonZeroUpstreamCode(JsonNode rootNode) {
        JsonNode codeNode = rootNode != null ? rootNode.get("code") : null;
        int code = codeNode != null ? codeNode.asInt(0) : 0;
        return code != 0 ? code : null;
    }

    /**
     * 提取上游响应 message，为空时返回默认值。
     */
    public static String upstreamMessageOrDefault(JsonNode rootNode, String defaultValue) {
        return rootNode != null && rootNode.has("message")
            ? Texts.defaultIfBlank(rootNode.get("message").asText(), defaultValue)
            : defaultValue;
    }

    /**
     * DEBUG 级别记录上游原始响应体（截断至 800 字符）。
     */
    public static void logUpstreamBodyDebug(Logger callerLog, String prefix, String responseBody) {
        if (callerLog.isDebugEnabled()) {
            callerLog.debug("{}: {}", prefix, Texts.truncate(Texts.nullToEmpty(responseBody), 800));
        }
    }

    public UpstreamJsonResult executeSignedJsonPost(String fullUrl, Map<String, String> headers, Object body) throws Exception {
        return toJsonResult(executeSignedRawPost(fullUrl, headers, body));
    }

    private UpstreamJsonResult toJsonResult(UpstreamRawResult raw) throws Exception {
        if (raw == null) {
            return null;
        }
        JsonNode jsonBody = objectMapper.readTree(raw.responseBody);
        return new UpstreamJsonResult(raw.response, raw.responseBody, jsonBody);
    }

    public UpstreamRawResult executeSignedRawGet(String fullUrl, Map<String, String> headers) throws Exception {
        return executeSignedRaw(fullUrl, headers, HttpMethod.GET, null, false);
    }

    public UpstreamRawResult executeSignedRawGetRateLimited(String fullUrl, Map<String, String> headers) throws Exception {
        return executeSignedRaw(fullUrl, headers, HttpMethod.GET, null, true);
    }

    public UpstreamRawResult executeSignedRawPost(String fullUrl, Map<String, String> headers, Object body) throws Exception {
        return executeSignedRaw(fullUrl, headers, HttpMethod.POST, body, false);
    }

    private UpstreamRawResult executeSignedRaw(
        String fullUrl,
        Map<String, String> headers,
        HttpMethod method,
        Object body,
        boolean rateLimit
    ) throws Exception {
        Map<String, String> requestHeaders = Objects.requireNonNullElse(headers, Map.of());
        Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeadersSync(fullUrl, requestHeaders);
        if (signedHeaders == null || signedHeaders.isEmpty()) {
            return null;
        }

        HttpHeaders httpHeaders = mergeHttpHeaders(requestHeaders, signedHeaders);

        if (rateLimit) {
            upstreamRateLimiter.acquire();
        }
        HttpEntity<?> entity = buildHttpEntity(body, httpHeaders);
        ResponseEntity<byte[]> response = restTemplate.exchange(URI.create(fullUrl), method, entity, byte[].class);
        String responseBody = GzipUtils.decodeUpstreamResponse(response);
        return new UpstreamRawResult(response, responseBody);
    }

    private static HttpHeaders mergeHttpHeaders(Map<String, String> requestHeaders, Map<String, String> signedHeaders) {
        HttpHeaders httpHeaders = new HttpHeaders();
        if (requestHeaders != null) {
            requestHeaders.forEach(httpHeaders::set);
        }
        if (signedHeaders != null) {
            signedHeaders.forEach(httpHeaders::set);
        }
        return httpHeaders;
    }

    private static HttpEntity<?> buildHttpEntity(Object body, HttpHeaders httpHeaders) {
        return body == null ? new HttpEntity<>(httpHeaders) : new HttpEntity<>(body, httpHeaders);
    }

    public static String resolveRetryReason(String message) {
        String normalized = Texts.trimToEmpty(message);
        if (containsIllegalAccess(normalized)) {
            return REASON_ILLEGAL_ACCESS;
        }
        if (normalized.contains(EX_EMPTY_UPSTREAM_RESPONSE) || normalized.contains(EX_JACKSON_EMPTY_CONTENT)) {
            return REASON_UPSTREAM_EMPTY;
        }
        if (normalized.contains(EX_GZIP_NOT_IN_FORMAT)) {
            return REASON_UPSTREAM_GZIP;
        }
        if (normalized.contains(REASON_UPSTREAM_NON_JSON)) {
            return REASON_UPSTREAM_NON_JSON;
        }
        if (normalized.contains(EX_SIGNER_FAIL)) {
            return REASON_SIGNER_FAIL;
        }
        return null;
    }

    public static boolean containsIllegalAccess(String value) {
        return Texts.hasText(value) && value.contains(REASON_ILLEGAL_ACCESS);
    }

    public static boolean isLikelyRiskControl(String message) {
        if (!Texts.hasText(message)) {
            return false;
        }
        String normalized = Texts.trimToEmpty(message).toLowerCase(Locale.ROOT);
        return normalized.contains("illegal_access")
            || normalized.contains("risk")
            || normalized.contains("风控")
            || normalized.contains("forbidden")
            || normalized.contains("permission");
    }

    public record UpstreamRawResult(ResponseEntity<byte[]> response, String responseBody) {}

    public record UpstreamJsonResult(ResponseEntity<byte[]> response, String responseBody, JsonNode jsonBody) {}
}
