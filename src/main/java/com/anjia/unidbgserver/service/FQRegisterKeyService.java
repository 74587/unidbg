package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.anjia.unidbgserver.utils.GzipUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FQNovel RegisterKey缓存服务
 * 在启动时获取registerkey并缓存，支持keyver比较和自动刷新
 */
@Slf4j
@Service
public class FQRegisterKeyService {

    @Resource(name = "fqEncryptWorker")
    private FQEncryptServiceWorker fqEncryptServiceWorker;

    @Resource
    private FQApiProperties fqApiProperties;

    @Resource
    private FQApiUtils fqApiUtils;

    @Resource
    private UpstreamRateLimiter upstreamRateLimiter;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    // 缓存的registerkey响应，按keyver分组
    private final LinkedHashMap<Long, CacheEntry> cachedRegisterKeys = new LinkedHashMap<Long, CacheEntry>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CacheEntry> eldest) {
            int maxEntries = 32;
            if (fqApiProperties != null) {
                maxEntries = Math.max(1, fqApiProperties.getRegisterKeyCacheMaxEntries());
            }
            return size() > maxEntries;
        }
    };

    // 当前默认的registerkey响应
    private volatile FqRegisterKeyResponse currentRegisterKey;
    private volatile long currentRegisterKeyExpiresAtMs = 0L;

    /**
     * 获取默认FQ变量（延迟初始化）
     */
    private FqVariable getDefaultFqVariable() {
        return new FqVariable(fqApiProperties);
    }

    /**
     * 获取registerkey，支持keyver比较和自动刷新
     *
     * @param requiredKeyver 需要的keyver，如果为null或<=0则使用当前缓存的key
     * @return RegisterKey响应
     */
    public synchronized FqRegisterKeyResponse getRegisterKey(Long requiredKeyver) throws Exception {
        Long normalizedKeyver = normalizeKeyver(requiredKeyver);

        // 如果没有指定有效keyver，返回当前缓存的key
        if (normalizedKeyver == null) {
            if (requiredKeyver != null) {
                log.debug("收到无效keyver({})，将使用当前缓存的registerkey", requiredKeyver);
            }
            if (isCurrentRegisterKeyValid()) {
                return currentRegisterKey;
            }
            // 如果当前没有缓存的key，获取一个新的
            return refreshRegisterKey();
        }

        // 检查是否已经缓存了指定keyver的key
        FqRegisterKeyResponse cached = getCachedIfPresent(normalizedKeyver);
        if (cached != null && cached.getData() != null) {
            log.debug("使用缓存的registerkey，keyver: {}", normalizedKeyver);
            return cached;
        }

        // 如果当前缓存的key的keyver匹配，直接返回
        if (isCurrentRegisterKeyValid() && currentRegisterKey.getData().getKeyver() == normalizedKeyver.longValue()) {
            log.debug("当前registerkey版本匹配，keyver: {}", normalizedKeyver);
            return currentRegisterKey;
        }

        // keyver不匹配，但不应该刷新（因为刷新只会获取最新的keyver）
        // 记录警告并返回当前key，让调用方处理版本不匹配的情况
        Object currentKeyver = (currentRegisterKey != null && currentRegisterKey.getData() != null)
            ? currentRegisterKey.getData().getKeyver() : "无";
        log.warn("registerkey 版本不匹配 - 当前keyver={}，需要keyver={}。无法获取历史keyver，将使用当前key尝试解密",
                currentKeyver, normalizedKeyver);
        
        // 如果当前key有效，返回它（即使版本不匹配）
        if (isCurrentRegisterKeyValid()) {
            return currentRegisterKey;
        }
        
        // 如果当前key无效，刷新一个新的
        return refreshRegisterKey();
    }

    private Long normalizeKeyver(Long keyver) {
        if (keyver == null || keyver <= 0) {
            return null;
        }
        return keyver;
    }

    /**
     * 刷新registerkey
     *
     * @return 新的RegisterKey响应
     */
    public synchronized FqRegisterKeyResponse refreshRegisterKey() throws Exception {
        log.debug("刷新 registerkey...");
        FqRegisterKeyResponse response = fetchRegisterKey();

        if (response == null) {
            throw new IllegalStateException("刷新registerkey失败，响应为空");
        }

        if (response.getCode() != 0) {
            throw new IllegalStateException("刷新registerkey失败，上游返回 code=" + response.getCode()
                + ", message=" + response.getMessage());
        }

        if (response.getData() == null || response.getData().getKey() == null || response.getData().getKey().trim().isEmpty()) {
            throw new IllegalStateException("刷新registerkey失败，响应缺少有效key");
        }

        long keyver = response.getData().getKeyver();
        if (keyver <= 0) {
            throw new IllegalStateException("刷新registerkey失败，响应缺少有效keyver");
        }

        long expiresAt = putCache(keyver, response);
        currentRegisterKey = response;
        currentRegisterKeyExpiresAtMs = expiresAt;
        log.debug("registerkey 刷新成功：keyver={}", keyver);
        return response;
    }

    /**
     * 实际获取registerkey的方法
     *
     * @return RegisterKey响应
     */
    private FqRegisterKeyResponse fetchRegisterKey() throws Exception {
        FqVariable var = getDefaultFqVariable();

        // 使用工具类构建URL和参数
        String url = fqApiUtils.getBaseUrl() + "/reading/crypt/registerkey";
        Map<String, String> params = fqApiUtils.buildCommonApiParams(var);
        String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

        // 生成统一的时间戳
        long currentTime = System.currentTimeMillis();

        // 使用工具类构建请求头
        Map<String, String> headers = fqApiUtils.buildRegisterKeyHeaders(currentTime);

        // 使用现有的签名服务生成签名
        Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeadersSync(fullUrl, headers);
        if (signedHeaders == null || signedHeaders.isEmpty()) {
            throw new IllegalStateException("签名生成失败，无法请求 registerkey");
        }

        // 发起API请求
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::set);
        signedHeaders.forEach(httpHeaders::set);

        // 创建请求载荷
        FqRegisterKeyPayload payload = buildRegisterKeyPayload(var);
        HttpEntity<FqRegisterKeyPayload> entity = new HttpEntity<>(payload, httpHeaders);

        log.debug("发送registerkey请求到: {}", fullUrl);
        log.debug("请求时间戳: {}", currentTime);
        log.debug("签名请求头: {}", httpHeaders);
        log.debug("请求载荷: content={}, keyver={}", payload.getContent(), payload.getKeyver());

        upstreamRateLimiter.acquire();
        ResponseEntity<byte[]> response = restTemplate.exchange(fullUrl, HttpMethod.POST, entity, byte[].class);

        String responseBody = GzipUtils.decompressGzipResponse(response.getBody());
        if (log.isDebugEnabled()) {
            log.debug("registerkey原始响应: {}", responseBody.length() > 800 ? responseBody.substring(0, 800) + "..." : responseBody);
        }

        JsonNode root = objectMapper.readTree(responseBody);
        FqRegisterKeyResponse parsed = objectMapper.treeToValue(root, FqRegisterKeyResponse.class);

        if (parsed == null) {
            throw new IllegalStateException("registerkey 响应解析失败: body为空");
        }

        log.debug("registerkey请求响应: code={}, message={}, keyver={}",
            parsed.getCode(), parsed.getMessage(),
            parsed.getData() != null ? parsed.getData().getKeyver() : "null");

        return parsed;
    }

    /**
     * 获取指定keyver的解密密钥
     *
     * @param requiredKeyver 需要的keyver
     * @return 解密密钥（十六进制字符串）
     */
    public String getDecryptionKey(Long requiredKeyver) throws Exception {
        FqRegisterKeyResponse registerKeyResponse = getRegisterKey(requiredKeyver);
        if (registerKeyResponse.getData() == null) {
            throw new IllegalStateException("registerkey 响应 data 为空");
        }
        return FqCrypto.getRealKey(registerKeyResponse.getData().getKey());
    }

    private FqRegisterKeyPayload buildRegisterKeyPayload(FqVariable var) throws Exception {
        FqCrypto crypto = new FqCrypto(FqCrypto.REG_KEY);
        String content = crypto.newRegisterKeyContent(var.getServerDeviceId(), "0");
        return new FqRegisterKeyPayload(content, 1L);
    }

    /**
     * 清除缓存
     */
    public void clearCache() {
        synchronized (this) {
            cachedRegisterKeys.clear();
            currentRegisterKey = null;
            currentRegisterKeyExpiresAtMs = 0L;
        }
        log.info("registerkey缓存已清除");
    }

    /**
     * 获取缓存状态信息
     */
    public Map<String, Object> getCacheStatus() {
        synchronized (this) {
            Map<String, Object> status = new HashMap<>();
            status.put("cachedKeyversCount", cachedRegisterKeys.size());
            status.put("cachedKeyvers", new java.util.ArrayList<>(cachedRegisterKeys.keySet()));
            status.put("currentKeyver", currentRegisterKey != null && currentRegisterKey.getData() != null ? currentRegisterKey.getData().getKeyver() : null);
            status.put("cacheMaxEntries", Math.max(1, fqApiProperties.getRegisterKeyCacheMaxEntries()));
            status.put("cacheTtlMs", Math.max(0L, fqApiProperties.getRegisterKeyCacheTtlMs()));
            return status;
        }
    }

    private FqRegisterKeyResponse getCachedIfPresent(Long keyver) {
        CacheEntry entry = cachedRegisterKeys.get(keyver);
        if (entry == null) {
            return null;
        }
        long ttlMs = Math.max(0L, fqApiProperties.getRegisterKeyCacheTtlMs());
        if (ttlMs > 0 && entry.expiresAtMs < System.currentTimeMillis()) {
            cachedRegisterKeys.remove(keyver);
            return null;
        }
        return entry.value;
    }

    private long putCache(long keyver, FqRegisterKeyResponse value) {
        long expiresAt = computeExpiresAt(System.currentTimeMillis());
        cachedRegisterKeys.put(keyver, new CacheEntry(value, expiresAt));
        return expiresAt;
    }

    private boolean isCurrentRegisterKeyValid() {
        if (currentRegisterKey == null || currentRegisterKey.getData() == null) {
            return false;
        }
        if (isExpired(currentRegisterKeyExpiresAtMs)) {
            currentRegisterKey = null;
            currentRegisterKeyExpiresAtMs = 0L;
            return false;
        }
        return true;
    }

    private long computeExpiresAt(long nowMs) {
        long ttlMs = Math.max(0L, fqApiProperties.getRegisterKeyCacheTtlMs());
        return ttlMs <= 0 ? Long.MAX_VALUE : (nowMs + ttlMs);
    }

    private boolean isExpired(long expiresAtMs) {
        return expiresAtMs > 0L && expiresAtMs < System.currentTimeMillis();
    }

    private static final class CacheEntry {
        private final FqRegisterKeyResponse value;
        private final long expiresAtMs;

        private CacheEntry(FqRegisterKeyResponse value, long expiresAtMs) {
            this.value = value;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
