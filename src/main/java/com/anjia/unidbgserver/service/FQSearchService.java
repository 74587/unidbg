package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.constants.FQConstants;
import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.anjia.unidbgserver.utils.FQDirectoryResponseTransformer;
import com.anjia.unidbgserver.utils.FQSearchResponseParser;
import com.anjia.unidbgserver.utils.GzipUtils;
import com.anjia.unidbgserver.utils.LocalCacheFactory;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.anjia.unidbgserver.utils.SearchIdExtractor;
import com.anjia.unidbgserver.utils.Texts;
import com.github.benmanes.caffeine.cache.Cache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * FQ书籍搜索和目录服务
 * 提供书籍搜索、目录获取等功能
 */
@Slf4j
@Service
public class FQSearchService {

    @Resource(name = "fqEncryptWorker")
    private FQEncryptServiceWorker fqEncryptServiceWorker;

    @Resource
    private FQApiProperties fqApiProperties;

    @Resource
    private FQApiUtils fqApiUtils;

    @Resource
    private UpstreamRateLimiter upstreamRateLimiter;

    @Resource
    private FQDeviceRotationService deviceRotationService;

    @Resource
    private AutoRestartService autoRestartService;

    @Resource
    private FQDownloadProperties downloadProperties;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private FQSearchRequestEnricher searchRequestEnricher;

    @Resource(name = "applicationTaskExecutor")
    private Executor taskExecutor;

    private Cache<String, FQSearchResponse> searchCache;
    private Cache<String, FQDirectoryResponse> directoryApiCache;
    private final ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<FQSearchResponse>>> inflightSearch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<FQDirectoryResponse>>> inflightDirectory = new ConcurrentHashMap<>();

    @PostConstruct
    public void initCaches() {
        int searchMax = Math.max(1, downloadProperties.getSearchCacheMaxEntries());
        long searchTtl = Math.max(0L, downloadProperties.getSearchCacheTtlMs());
        int directoryMax = Math.max(1, downloadProperties.getApiDirectoryCacheMaxEntries());
        long directoryTtl = Math.max(0L, downloadProperties.getApiDirectoryCacheTtlMs());
        this.searchCache = LocalCacheFactory.build(searchMax, searchTtl);
        this.directoryApiCache = LocalCacheFactory.build(directoryMax, directoryTtl);
    }

    private static String snippet(String value, int maxLen) {
        if (value == null) return "";
        if (value.length() <= maxLen) return value;
        return value.substring(0, Math.max(0, maxLen)) + "...";
    }

    private static boolean hasBooks(FQNovelResponse<FQSearchResponse> response) {
        if (response == null || response.getData() == null) {
            return false;
        }
        List<FQSearchResponse.BookItem> books = response.getData().getBooks();
        return books != null && !books.isEmpty();
    }

    private static String extractSearchId(FQNovelResponse<FQSearchResponse> response) {
        if (response == null || response.getData() == null) {
            return "";
        }
        String id = response.getData().getSearchId();
        return id == null ? "" : id.trim();
    }

    private void sleepRetryBackoff(int attempt) {
        long base = Math.max(0L, downloadProperties.getRetryDelayMs());
        long max = Math.max(base, downloadProperties.getRetryMaxDelayMs());
        long delay = base;
        for (int i = 1; i < attempt; i++) {
            delay = Math.min(max, delay * 2);
        }
        long jitter = ThreadLocalRandom.current().nextLong(150L, 450L);
        long total = Math.min(max, delay + jitter);
        if (total <= 0) {
            return;
        }
        try {
            Thread.sleep(total);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取默认FQ变量（延迟初始化）
     */
    private FqVariable getDefaultFqVariable() {
        return new FqVariable(fqApiProperties);
    }

    private static String normalizeCachePart(String value) {
        if (value == null) {
            return "";
        }
        return value.trim();
    }

    private static String buildSearchCacheKey(FQSearchRequest request) {
        if (request == null) {
            return null;
        }
        String query = normalizeCachePart(request.getQuery());
        if (query.isEmpty()) {
            return null;
        }
        int offset = request.getOffset() != null ? request.getOffset() : 0;
        int count = request.getCount() != null ? request.getCount() : 20;
        int tabType = request.getTabType() != null ? request.getTabType() : 1;
        String searchId = normalizeCachePart(request.getSearchId());
        return query + "|" + offset + "|" + count + "|" + tabType + "|" + searchId;
    }

    private static String buildDirectoryCacheKey(FQDirectoryRequest request) {
        if (request == null) {
            return null;
        }
        String bookId = normalizeCachePart(request.getBookId());
        if (bookId.isEmpty()) {
            return null;
        }
        int bookType = request.getBookType() != null ? request.getBookType() : 0;
        boolean minimalResponse = Boolean.TRUE.equals(request.getMinimalResponse());
        boolean needVersion = minimalResponse
            ? false
            : (request.getNeedVersion() == null || request.getNeedVersion());
        String itemMd5 = normalizeCachePart(request.getItemDataListMd5());
        String catalogMd5 = normalizeCachePart(request.getCatalogDataMd5());
        String bookInfoMd5 = normalizeCachePart(request.getBookInfoMd5());
        return bookId + "|" + bookType + "|" + needVersion + "|" + minimalResponse + "|" + itemMd5 + "|" + catalogMd5 + "|" + bookInfoMd5;
    }

    private static boolean isSearchCacheable(FQNovelResponse<FQSearchResponse> response) {
        return response != null
            && response.getCode() != null
            && response.getCode() == 0
            && response.getData() != null;
    }

    private static boolean isDirectoryCacheable(FQNovelResponse<FQDirectoryResponse> response) {
        return response != null
            && response.getCode() != null
            && response.getCode() == 0
            && response.getData() != null;
    }

    private static final class UpstreamGetResult {
        private final ResponseEntity<byte[]> response;
        private final String responseBody;
        private final JsonNode jsonBody;

        private UpstreamGetResult(ResponseEntity<byte[]> response, String responseBody, JsonNode jsonBody) {
            this.response = response;
            this.responseBody = responseBody;
            this.jsonBody = jsonBody;
        }
    }

    private UpstreamGetResult executeSignedGet(String fullUrl, Map<String, String> headers, String signatureFailLog) throws Exception {
        Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeadersSync(fullUrl, headers);
        if (signedHeaders == null || signedHeaders.isEmpty()) {
            log.error("{} - url: {}", signatureFailLog, fullUrl);
            return null;
        }

        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::set);
        signedHeaders.forEach(httpHeaders::set);

        HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
        upstreamRateLimiter.acquire();
        ResponseEntity<byte[]> response = restTemplate.exchange(URI.create(fullUrl), HttpMethod.GET, entity, byte[].class);
        String responseBody = GzipUtils.decodeUpstreamResponse(response);
        JsonNode jsonBody = objectMapper.readTree(responseBody);
        return new UpstreamGetResult(response, responseBody, jsonBody);
    }

    private <T> CompletableFuture<FQNovelResponse<T>> loadWithRequestCache(
        String cacheKey,
        Cache<String, T> cache,
        ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<T>>> inflight,
        Supplier<FQNovelResponse<T>> loader,
        Predicate<FQNovelResponse<T>> cacheablePredicate,
        Runnable cacheHitHook,
        String cacheLabel
    ) {
        if (cacheKey == null) {
            return CompletableFuture.supplyAsync(loader, taskExecutor);
        }

        T cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            if (cacheHitHook != null) {
                cacheHitHook.run();
            }
            if (log.isDebugEnabled()) {
                log.debug("{}缓存命中 - key: {}", cacheLabel, cacheKey);
            }
            return CompletableFuture.completedFuture(FQNovelResponse.success(cached));
        }

        CompletableFuture<FQNovelResponse<T>> existing = inflight.get(cacheKey);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<FQNovelResponse<T>> created = new CompletableFuture<>();
        existing = inflight.putIfAbsent(cacheKey, created);
        if (existing != null) {
            return existing;
        }

        CompletableFuture.supplyAsync(loader, taskExecutor)
            .whenComplete((response, ex) -> {
                try {
                    if (ex != null) {
                        created.completeExceptionally(ex);
                        return;
                    }
                    if (cacheablePredicate != null && cacheablePredicate.test(response)) {
                        cache.put(cacheKey, response.getData());
                    }
                    created.complete(response);
                } finally {
                    inflight.remove(cacheKey, created);
                }
            });

        return created;
    }

    /**
     * 搜索书籍 - 增强版，支持两阶段搜索
     *
     * @param searchRequest 搜索请求参数
     * @return 搜索结果
     */
    public CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooksEnhanced(FQSearchRequest searchRequest) {
        if (ProcessLifecycle.isShuttingDown()) {
            return CompletableFuture.completedFuture(FQNovelResponse.error("服务正在退出中，请稍后重试"));
        }

        String cacheKey = buildSearchCacheKey(searchRequest);
        return loadWithRequestCache(
            cacheKey,
            searchCache,
            inflightSearch,
            () -> searchBooksEnhancedInternal(searchRequest),
            FQSearchService::isSearchCacheable,
            autoRestartService::recordSuccess,
            "搜索"
        );
    }

    private FQNovelResponse<FQSearchResponse> searchBooksEnhancedInternal(FQSearchRequest searchRequest) {
        try {
            if (ProcessLifecycle.isShuttingDown()) {
                return FQNovelResponse.error("服务正在退出中，请稍后重试");
            }

            if (searchRequest == null) {
                return FQNovelResponse.error("搜索请求不能为空");
            }

            searchRequestEnricher.enrich(searchRequest);

            // 如果用户已经提供了search_id，直接进行搜索
            if (Texts.hasText(searchRequest.getSearchId())) {
                FQNovelResponse<FQSearchResponse> response = performSearchWithId(searchRequest);
                if (response != null && response.getCode() != null && response.getCode() == 0) {
                    autoRestartService.recordSuccess();
                } else {
                    autoRestartService.recordFailure("SEARCH_WITH_ID_FAIL");
                }
                return response;
            }

            // 第一阶段：获取search_id
            FQSearchRequest firstRequest = createFirstPhaseRequest(searchRequest);
            FQNovelResponse<FQSearchResponse> firstResponse = performSearchInternal(firstRequest);

            if (firstResponse.getCode() != 0) {
                // 某些风控/异常场景下，上游可能返回非 0；尝试切换设备后再试一次
                if (shouldRotate(firstResponse.getMessage())) {
                    DeviceInfo rotated = deviceRotationService.rotateIfNeeded("SEARCH_PHASE1_FAIL");
                    if (rotated != null) {
                        firstResponse = performSearchInternal(firstRequest);
                        if (firstResponse.getCode() == 0) {
                            // 继续走后续逻辑
                        } else {
                            log.warn("第一阶段搜索失败 - code: {}, message: {}", firstResponse.getCode(), firstResponse.getMessage());
                            autoRestartService.recordFailure("SEARCH_PHASE1_FAIL");
                            return firstResponse;
                        }
                    } else {
                        log.warn("第一阶段搜索失败 - code: {}, message: {}", firstResponse.getCode(), firstResponse.getMessage());
                        autoRestartService.recordFailure("SEARCH_PHASE1_FAIL");
                        return firstResponse;
                    }
                }
                if (firstResponse.getCode() != 0) {
                    log.warn("第一阶段搜索失败 - code: {}, message: {}", firstResponse.getCode(), firstResponse.getMessage());
                    autoRestartService.recordFailure("SEARCH_PHASE1_FAIL");
                    return firstResponse;
                }
            }

            String firstSearchId = extractSearchId(firstResponse);
            if (Texts.isBlank(firstSearchId)) {
                // 如果第一阶段已返回可用书籍列表（即使缺 search_id），直接返回，避免误判为“不可用”
                if (hasBooks(firstResponse)) {
                    log.info("第一阶段未返回search_id，但已返回书籍结果，跳过第二阶段");
                    autoRestartService.recordSuccess();
                    return firstResponse;
                }

                // 自愈：对“缺 search_id 且无结果”进行有限重试 + 轮换（最多轮换到池内其他设备）
                int perDeviceRetries = Math.max(1, Math.min(2, downloadProperties.getMaxRetries()));
                int maxDevices = Math.max(1, fqApiProperties.getDevicePool() != null ? fqApiProperties.getDevicePool().size() : 1);

                FQNovelResponse<FQSearchResponse> candidate = firstResponse;
                String candidateSearchId = "";

                for (int deviceAttempt = 0; deviceAttempt < maxDevices; deviceAttempt++) {
                    if (deviceAttempt > 0) {
                        DeviceInfo rotated = deviceRotationService.forceRotate("SEARCH_NO_SEARCH_ID");
                        if (rotated == null) {
                            break;
                        }
                    }

                    for (int retryAttempt = 0; retryAttempt < perDeviceRetries; retryAttempt++) {
                        if (deviceAttempt != 0 || retryAttempt != 0) {
                            sleepRetryBackoff(deviceAttempt * perDeviceRetries + retryAttempt + 1);
                            candidate = performSearchInternal(firstRequest);
                        }

                        candidateSearchId = extractSearchId(candidate);
                        if (Texts.hasText(candidateSearchId)) {
                            firstResponse = candidate;
                            firstSearchId = candidateSearchId;
                            break;
                        }
                        if (hasBooks(candidate)) {
                            log.info("第一阶段未返回search_id，但重试后已返回书籍结果，跳过第二阶段");
                            autoRestartService.recordSuccess();
                            return candidate;
                        }
                    }

                    if (Texts.hasText(firstSearchId)) {
                        break;
                    }
                }

                if (Texts.isBlank(firstSearchId)) {
                    log.warn("第一阶段搜索未返回search_id（可能风控/上游异常），建议稍后重试");
                    autoRestartService.recordFailure("SEARCH_NO_SEARCH_ID");
                    return FQNovelResponse.error("上游未返回search_id（可能风控/上游异常），请稍后重试");
                }
            }

            String searchId = firstSearchId;

            // 随机延迟（模拟真实用户行为）
            try {
                long delay = ThreadLocalRandom.current().nextLong(
                    FQConstants.Search.MIN_SEARCH_DELAY_MS,
                    FQConstants.Search.MAX_SEARCH_DELAY_MS + 1
                );
                Thread.sleep(delay);
                searchRequest.setLastSearchPageInterval((int) delay); // 设置间隔时间
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("延迟被中断", e);
            }

            // 第二阶段：使用search_id进行搜索
            FQSearchRequest secondRequest = createSecondPhaseRequest(searchRequest, searchId);
            FQNovelResponse<FQSearchResponse> secondResponse = performSearchInternal(secondRequest);

            // 确保返回结果包含search_id
            if (secondResponse.getCode() == 0 && secondResponse.getData() != null) {
                secondResponse.getData().setSearchId(searchId);
            }

            if (secondResponse != null && secondResponse.getCode() != null && secondResponse.getCode() == 0) {
                autoRestartService.recordSuccess();
            } else {
                autoRestartService.recordFailure("SEARCH_PHASE2_FAIL");
            }
            return secondResponse;

        } catch (Exception e) {
            String query = searchRequest != null ? searchRequest.getQuery() : null;
            log.error("增强搜索失败 - query: {}", query, e);
            autoRestartService.recordFailure("SEARCH_EXCEPTION");
            return FQNovelResponse.error("增强搜索失败: " + e.getMessage());
        }
    }

    /**
     * 创建第一阶段请求（获取search_id）
     */
    private FQSearchRequest createFirstPhaseRequest(FQSearchRequest originalRequest) {
        FQSearchRequest firstRequest = new FQSearchRequest();

        // 复制所有基本参数
        copyBasicParameters(originalRequest, firstRequest);

        // 第一阶段特定设置
        firstRequest.setIsFirstEnterSearch(true);
        firstRequest.setClientAbInfo(originalRequest.getClientAbInfo()); // 包含client_ab_info
        firstRequest.setLastSearchPageInterval(0); // 第一次调用为0

        // 确保passback与offset相同
        ensurePassback(firstRequest);

        return firstRequest;
    }

    /**
     * 创建第二阶段请求（使用search_id）
     */
    private FQSearchRequest createSecondPhaseRequest(FQSearchRequest originalRequest, String searchId) {
        FQSearchRequest secondRequest = new FQSearchRequest();

        // 复制所有基本参数
        copyBasicParameters(originalRequest, secondRequest);

        // 第二阶段特定设置
        secondRequest.setSearchId(searchId);
        secondRequest.setIsFirstEnterSearch(false); // 第二次调用设为false
        // 不设置client_ab_info（在buildSearchParams中会被跳过）

        // 确保passback与offset相同
        ensurePassback(secondRequest);

        return secondRequest;
    }

    /**
     * 复制基本参数
     */
    private void copyBasicParameters(FQSearchRequest source, FQSearchRequest target) {
        BeanUtils.copyProperties(source, target);
    }

    private static void ensurePassback(FQSearchRequest request) {
        if (request != null && request.getPassback() == null) {
            request.setPassback(request.getOffset());
        }
    }

    /**
     * 执行带search_id的搜索
     */
    private FQNovelResponse<FQSearchResponse> performSearchWithId(FQSearchRequest searchRequest) {
        // 确保is_first_enter_search为false，不包含client_ab_info
        searchRequest.setIsFirstEnterSearch(false);

        searchRequestEnricher.enrich(searchRequest);

        // 确保passback与offset相同
        ensurePassback(searchRequest);

        return performSearchInternal(searchRequest);
    }

    /**
     * 执行实际的搜索请求
     */
    private FQNovelResponse<FQSearchResponse> performSearchInternal(FQSearchRequest searchRequest) {
        try {
            FqVariable var = getDefaultFqVariable();

            // 构建搜索URL和参数
            String url = fqApiUtils.getSearchApiBaseUrl()
                + "/reading/bookapi/search/tab/v";
            Map<String, String> params = fqApiUtils.buildSearchParams(var, searchRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            // 构建请求头
            Map<String, String> headers = fqApiUtils.buildSearchHeaders();

            UpstreamGetResult upstream = executeSignedGet(fullUrl, headers, "签名生成失败，终止请求");
            if (upstream == null) {
                return FQNovelResponse.error("签名生成失败");
            }

            ResponseEntity<byte[]> response = upstream.response;
            String responseBody = upstream.responseBody;
            JsonNode jsonResponse = upstream.jsonBody;

            // 上游如果有 code/message，优先按其判断是否成功
            if (jsonResponse.has("code")) {
                int upstreamCode = jsonResponse.path("code").asInt(0);
                if (upstreamCode != 0) {
                    String upstreamMessage = jsonResponse.path("message").asText("upstream error");
                    log.warn("上游搜索接口返回失败 - code: {}, message: {}", upstreamCode, upstreamMessage);
                    return FQNovelResponse.error(upstreamCode, upstreamMessage);
                }
            }

            int tabType = searchRequest.getTabType() != null ? searchRequest.getTabType() : 1;
            FQSearchResponse searchResponse = FQSearchResponseParser.parseSearchResponse(jsonResponse, tabType);

            // 兜底：如果 parseSearchResponse 没取到 search_id，再做一次深度提取（含 root/data/log_pb 等）
            if (searchResponse != null && Texts.isBlank(searchResponse.getSearchId())) {
                String fromBody = SearchIdExtractor.deepFind(jsonResponse);
                if (Texts.hasText(fromBody)) {
                    searchResponse.setSearchId(fromBody);
                }
            }

            // 兜底：部分情况下 search_id 可能在响应头里
            if (searchResponse != null && Texts.isBlank(searchResponse.getSearchId())) {
                String fromHeader = Texts.firstNonBlank(
                    response.getHeaders().getFirst("search_id"),
                    response.getHeaders().getFirst("search-id"),
                    response.getHeaders().getFirst("x-search-id"),
                    response.getHeaders().getFirst("x-fq-search-id")
                );
                if (Texts.hasText(fromHeader)) {
                    searchResponse.setSearchId(fromHeader);
                }
            }

            if (Boolean.TRUE.equals(searchRequest.getIsFirstEnterSearch())
                && (searchResponse == null || Texts.isBlank(searchResponse.getSearchId()))
                && log.isDebugEnabled()) {
                log.debug("第一阶段搜索未返回search_id，原始响应: {}", snippet(responseBody, 1200));
            }

            return FQNovelResponse.success(searchResponse);

        } catch (Exception e) {
            log.error("搜索请求失败 - query: {}", searchRequest.getQuery(), e);
            return FQNovelResponse.error("搜索请求失败: " + e.getMessage());
        }
    }

    private static boolean shouldRotate(String message) {
        if (Texts.isBlank(message)) {
            return false;
        }
        String m = message.toLowerCase(Locale.ROOT);
        return m.contains("illegal_access")
            || m.contains("risk")
            || m.contains("风控")
            || m.contains("forbidden")
            || m.contains("permission");
    }

    /**
     * 获取书籍目录（增强版）
     *
     * @param directoryRequest 目录请求参数
     * @return 书籍目录
     */
    public CompletableFuture<FQNovelResponse<FQDirectoryResponse>> getBookDirectory(FQDirectoryRequest directoryRequest) {
        if (ProcessLifecycle.isShuttingDown()) {
            return CompletableFuture.completedFuture(FQNovelResponse.error("服务正在退出中，请稍后重试"));
        }

        String cacheKey = buildDirectoryCacheKey(directoryRequest);
        return loadWithRequestCache(
            cacheKey,
            directoryApiCache,
            inflightDirectory,
            () -> getBookDirectoryInternal(directoryRequest),
            FQSearchService::isDirectoryCacheable,
            null,
            "目录"
        );
    }

    private FQNovelResponse<FQDirectoryResponse> getBookDirectoryInternal(FQDirectoryRequest directoryRequest) {
        try {
            if (ProcessLifecycle.isShuttingDown()) {
                return FQNovelResponse.error("服务正在退出中，请稍后重试");
            }

            if (directoryRequest == null) {
                return FQNovelResponse.error("目录请求不能为空");
            }

            FqVariable var = getDefaultFqVariable();

            // 构建目录URL和参数
            String url = fqApiUtils.getSearchApiBaseUrl()
                + "/reading/bookapi/directory/all_items/v";
            Map<String, String> params = fqApiUtils.buildDirectoryParams(var, directoryRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            // 构建请求头
            Map<String, String> headers = fqApiUtils.buildCommonHeaders();

            UpstreamGetResult upstream = executeSignedGet(fullUrl, headers, "签名生成失败，终止目录请求");
            if (upstream == null) {
                return FQNovelResponse.error("签名生成失败");
            }

            String responseBody = upstream.responseBody;
            JsonNode rootNode = upstream.jsonBody;
            if (rootNode.has("code")) {
                int upstreamCode = rootNode.path("code").asInt(0);
                if (upstreamCode != 0) {
                    String upstreamMessage = rootNode.path("message").asText("upstream error");
                    if (log.isDebugEnabled()) {
                        log.debug("目录接口上游失败原始响应: {}", responseBody.length() > 800 ? responseBody.substring(0, 800) + "..." : responseBody);
                    }
                    return FQNovelResponse.error(upstreamCode, upstreamMessage);
                }
            }

            JsonNode dataNode = rootNode.get("data");
            if (dataNode == null || dataNode.isNull() || dataNode.isMissingNode()) {
                String upstreamMessage = rootNode.path("message").asText("upstream response missing data");
                if (log.isDebugEnabled()) {
                    log.debug("目录接口上游缺少data原始响应: {}", responseBody.length() > 800 ? responseBody.substring(0, 800) + "..." : responseBody);
                }
                return FQNovelResponse.error("获取书籍目录失败: " + upstreamMessage);
            }

            FQDirectoryResponse directoryResponse = objectMapper.treeToValue(dataNode, FQDirectoryResponse.class);
            if (directoryResponse == null) {
                String upstreamMessage = rootNode.path("message").asText("upstream parse error");
                return FQNovelResponse.error("获取书籍目录失败: " + upstreamMessage);
            }

            if (Boolean.TRUE.equals(directoryRequest.getMinimalResponse())) {
                FQDirectoryResponseTransformer.trimForMinimalResponse(directoryResponse);
            } else {
                // 增强章节列表数据
                FQDirectoryResponseTransformer.enhanceChapterList(directoryResponse);
            }

            return FQNovelResponse.success(directoryResponse);

        } catch (Exception e) {
            String bookId = directoryRequest != null ? directoryRequest.getBookId() : null;
            log.error("获取书籍目录失败 - bookId: {}", bookId, e);
            return FQNovelResponse.error("获取书籍目录失败: " + e.getMessage());
        }
    }

}
