package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.constants.FQConstants;
import com.anjia.unidbgserver.dto.FQDirectoryRequest;
import com.anjia.unidbgserver.dto.FQDirectoryResponse;
import com.anjia.unidbgserver.dto.FQNovelResponse;
import com.anjia.unidbgserver.dto.FQSearchRequest;
import com.anjia.unidbgserver.dto.FQSearchResponse;
import com.anjia.unidbgserver.dto.FqVariable;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.anjia.unidbgserver.utils.FQDirectoryResponseTransformer;
import com.anjia.unidbgserver.utils.FQSearchResponseParser;
import com.anjia.unidbgserver.utils.LocalCacheFactory;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.anjia.unidbgserver.utils.RetryBackoff;
import com.anjia.unidbgserver.utils.Texts;
import com.github.benmanes.caffeine.cache.Cache;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.function.Supplier;

@Service
public class FQSearchService {

    private static final Logger log = LoggerFactory.getLogger(FQSearchService.class);
    private static final String REASON_SEARCH_WITH_ID_FAIL = "SEARCH_WITH_ID_FAIL";
    private static final String REASON_SEARCH_PHASE1_FAIL = "SEARCH_PHASE1_FAIL";
    private static final String REASON_SEARCH_NO_SEARCH_ID = "SEARCH_NO_SEARCH_ID";
    private static final String REASON_SEARCH_PHASE2_FAIL = "SEARCH_PHASE2_FAIL";
    private static final String REASON_SEARCH_EXCEPTION = "SEARCH_EXCEPTION";
    private static final String ERROR_NO_SEARCH_ID =
        "上游未返回search_id（可能风控/上游异常），请稍后重试";
    private static final int RETRY_MAX_BACKOFF_EXPONENT = 10;
    private static final long RETRY_JITTER_MIN_MS = 150L;
    private static final long RETRY_JITTER_MAX_MS = 450L;
    private static final int SEARCH_ID_DEBUG_SNIPPET_LENGTH = 1200;
    private static final int UPSTREAM_BODY_DEBUG_MAX_LENGTH = 800;
    private static final long HIGH_FREQ_LOG_COOLDOWN_MS = 60_000L;

    private final FQApiProperties fqApiProperties;
    private final FQApiUtils fqApiUtils;
    private final FQDeviceRotationService deviceRotationService;
    private final AutoRestartService autoRestartService;
    private final FQDownloadProperties downloadProperties;
    private final ObjectMapper objectMapper;
    private final FQSearchRequestEnricher searchRequestEnricher;
    private final UpstreamSignedRequestService upstreamSignedRequestService;
    @Qualifier("applicationTaskExecutor")
    private final Executor taskExecutor;

    private Cache<String, FQSearchResponse> searchCache;
    private Cache<String, FQDirectoryResponse> directoryApiCache;
    private final ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<FQSearchResponse>>> inflightSearch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<FQNovelResponse<FQDirectoryResponse>>> inflightDirectory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> highFreqLogTimestamps = new ConcurrentHashMap<>();

    public FQSearchService(
        FQApiProperties fqApiProperties,
        FQApiUtils fqApiUtils,
        FQDeviceRotationService deviceRotationService,
        AutoRestartService autoRestartService,
        FQDownloadProperties downloadProperties,
        ObjectMapper objectMapper,
        FQSearchRequestEnricher searchRequestEnricher,
        UpstreamSignedRequestService upstreamSignedRequestService,
        @Qualifier("applicationTaskExecutor") Executor taskExecutor
    ) {
        this.fqApiProperties = fqApiProperties;
        this.fqApiUtils = fqApiUtils;
        this.deviceRotationService = deviceRotationService;
        this.autoRestartService = autoRestartService;
        this.downloadProperties = downloadProperties;
        this.objectMapper = objectMapper;
        this.searchRequestEnricher = searchRequestEnricher;
        this.upstreamSignedRequestService = upstreamSignedRequestService;
        this.taskExecutor = taskExecutor;
    }

    @PostConstruct
    public void initCaches() {
        int searchMax = Math.max(1, downloadProperties.getSearchCacheMaxEntries());
        long searchTtl = Math.max(0L, downloadProperties.getSearchCacheTtlMs());
        int directoryMax = Math.max(1, downloadProperties.getApiDirectoryCacheMaxEntries());
        long directoryTtl = Math.max(0L, downloadProperties.getApiDirectoryCacheTtlMs());
        this.searchCache = LocalCacheFactory.build(searchMax, searchTtl);
        this.directoryApiCache = LocalCacheFactory.build(directoryMax, directoryTtl);
    }

    private static String normalizeCachePart(String value) {
        return Texts.trimToEmpty(value);
    }

    private static int intOrDefault(Integer value, int defaultValue) {
        return value != null ? value : defaultValue;
    }

    private static Integer nonZeroUpstreamCode(JsonNode rootNode) {
        int upstreamCode = rootNode == null || !rootNode.has("code") ? 0 : rootNode.path("code").asInt(0);
        return upstreamCode == 0 ? null : upstreamCode;
    }

    private static String upstreamMessageOrDefault(JsonNode rootNode, String defaultValue) {
        return rootNode == null ? defaultValue : rootNode.path("message").asText(defaultValue);
    }

    private void logUpstreamBodyDebug(String prefix, String responseBody) {
        if (log.isDebugEnabled()) {
            log.debug("{}: {}", prefix, Texts.truncate(responseBody, UPSTREAM_BODY_DEBUG_MAX_LENGTH));
        }
    }

    private static String buildSearchCacheKey(FQSearchRequest request) {
        if (request == null) {
            return null;
        }
        String query = normalizeCachePart(request.getQuery());
        if (query.isEmpty()) {
            return null;
        }
        int offset = intOrDefault(request.getOffset(), 0);
        int count = intOrDefault(request.getCount(), 20);
        int tabType = intOrDefault(request.getTabType(), 1);
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
        int bookType = intOrDefault(request.getBookType(), 0);
        boolean minimalResponse = Boolean.TRUE.equals(request.getMinimalResponse());
        boolean needVersion = !minimalResponse && (request.getNeedVersion() == null || request.getNeedVersion());
        String itemMd5 = normalizeCachePart(request.getItemDataListMd5());
        String catalogMd5 = normalizeCachePart(request.getCatalogDataMd5());
        String bookInfoMd5 = normalizeCachePart(request.getBookInfoMd5());
        return bookId + "|" + bookType + "|" + needVersion + "|" + minimalResponse + "|" + itemMd5 + "|" + catalogMd5 + "|" + bookInfoMd5;
    }

    private static boolean isResponseSuccess(FQNovelResponse<?> response) {
        return response != null && response.getCode() != null && response.getCode() == 0;
    }

    private static boolean isResponseSuccessWithData(FQNovelResponse<?> response) {
        return isResponseSuccess(response) && response.getData() != null;
    }

    private static String extractSearchId(FQNovelResponse<FQSearchResponse> response) {
        return response != null && response.getData() != null ? Texts.trimToEmpty(response.getData().getSearchId()) : "";
    }

    private static boolean hasBooks(FQNovelResponse<FQSearchResponse> response) {
        List<FQSearchResponse.BookItem> books = response == null || response.getData() == null ? null : response.getData().getBooks();
        return books != null && !books.isEmpty();
    }

    private static <T> FQNovelResponse<T> signerFailResponse() {
        return FQNovelResponse.error("签名生成失败");
    }

    private static FQNovelResponse<FQDirectoryResponse> directoryFailure(String reason) {
        return FQNovelResponse.error("获取书籍目录失败: " + reason);
    }

    private static <T> FQNovelResponse<T> shuttingDownIfNeeded() {
        return ProcessLifecycle.isShuttingDown()
            ? FQNovelResponse.error("服务正在退出中，请稍后重试")
            : null;
    }

    private static <T> CompletableFuture<FQNovelResponse<T>> completedShuttingDownIfNeeded() {
        FQNovelResponse<T> response = shuttingDownIfNeeded();
        return response == null ? null : CompletableFuture.completedFuture(response);
    }

    private void recordSearchOutcome(FQNovelResponse<?> response, String failureReason) {
        if (isResponseSuccess(response)) {
            autoRestartService.recordSuccess();
        } else {
            autoRestartService.recordFailure(failureReason);
        }
    }

    private boolean shouldLogHighFreq(String key) {
        long now = System.currentTimeMillis();
        long last = highFreqLogTimestamps.getOrDefault(key, 0L);
        if (now - last < HIGH_FREQ_LOG_COOLDOWN_MS) {
            return false;
        }
        highFreqLogTimestamps.put(key, now);
        return true;
    }

    private UpstreamSignedRequestService.UpstreamJsonResult executeSignedJsonGetWithFailureLog(
        String fullUrl,
        Map<String, String> headers,
        String failureScene
    ) throws Exception {
        UpstreamSignedRequestService.UpstreamJsonResult upstream = upstreamSignedRequestService.executeSignedJsonGet(fullUrl, headers);
        if (upstream == null) {
            log.error("签名生成失败，终止{} - url: {}", failureScene, fullUrl);
        }
        return upstream;
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

    public CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooksEnhanced(FQSearchRequest searchRequest) {
        CompletableFuture<FQNovelResponse<FQSearchResponse>> shuttingDown = completedShuttingDownIfNeeded();
        if (shuttingDown != null) {
            return shuttingDown;
        }

        String cacheKey = buildSearchCacheKey(searchRequest);
        return loadWithRequestCache(
            cacheKey,
            searchCache,
            inflightSearch,
            () -> searchBooksEnhancedInternal(searchRequest),
            FQSearchService::isResponseSuccessWithData,
            autoRestartService::recordSuccess,
            "搜索"
        );
    }

    private FQNovelResponse<FQSearchResponse> searchBooksEnhancedInternal(FQSearchRequest searchRequest) {
        try {
            FQNovelResponse<FQSearchResponse> shuttingDown = shuttingDownIfNeeded();
            if (shuttingDown != null) {
                return shuttingDown;
            }

            if (searchRequest == null) {
                return FQNovelResponse.error("搜索请求不能为空");
            }
            FQSearchRequest enrichedRequest = copyRequest(searchRequest);
            searchRequestEnricher.enrich(enrichedRequest);

            if (Texts.hasText(enrichedRequest.getSearchId())) {
                enrichedRequest.setIsFirstEnterSearch(false);
                FQNovelResponse<FQSearchResponse> response = performSearchInternal(enrichedRequest);
                recordSearchOutcome(response, REASON_SEARCH_WITH_ID_FAIL);
                return response;
            }

            FQSearchRequest firstRequest = copyRequest(enrichedRequest);
            firstRequest.setIsFirstEnterSearch(true);
            firstRequest.setLastSearchPageInterval(FQConstants.Search.PHASE1_LAST_SEARCH_PAGE_INTERVAL);
            FQNovelResponse<FQSearchResponse> firstResponse = performSearchInternal(firstRequest);
            if (!isResponseSuccess(firstResponse)
                && UpstreamSignedRequestService.isLikelyRiskControl(firstResponse != null ? firstResponse.getMessage() : null)
                && deviceRotationService.rotateIfNeeded(REASON_SEARCH_PHASE1_FAIL) != null) {
                firstResponse = performSearchInternal(firstRequest);
            }
            if (!isResponseSuccess(firstResponse)) {
                if (shouldLogHighFreq("search.phase1.fail")) {
                    log.warn("第一阶段搜索失败 - code: {}, message: {}",
                        firstResponse != null ? firstResponse.getCode() : null,
                        firstResponse != null ? firstResponse.getMessage() : null);
                }
                autoRestartService.recordFailure(REASON_SEARCH_PHASE1_FAIL);
                return firstResponse != null ? firstResponse : FQNovelResponse.error("第一阶段搜索失败");
            }

            String searchId = extractSearchId(firstResponse);
            if (Texts.isBlank(searchId) && hasBooks(firstResponse)) {
                log.info("第一阶段未返回search_id，但已返回书籍结果，跳过第二阶段");
                autoRestartService.recordSuccess();
                return firstResponse;
            }
            if (Texts.isBlank(searchId)) {
                int perDeviceRetries = Math.max(
                    1,
                    Math.min(FQConstants.Search.MAX_RETRIES_PER_DEVICE, downloadProperties.getMaxRetries())
                );
                List<FQApiProperties.DeviceProfile> pool = fqApiProperties.getDevicePool();
                int maxDevices = Math.max(1, pool == null ? 0 : pool.size());
                FQNovelResponse<FQSearchResponse> candidate = firstResponse;

                searchRetry:
                for (int deviceAttempt = 0; deviceAttempt < maxDevices; deviceAttempt++) {
                    if (deviceAttempt > 0 && deviceRotationService.forceRotate(REASON_SEARCH_NO_SEARCH_ID) == null) {
                        break;
                    }
                    for (int retryAttempt = 0; retryAttempt < perDeviceRetries; retryAttempt++) {
                        if (!(deviceAttempt == 0 && retryAttempt == 0)) {
                            int retryOrdinal = deviceAttempt * perDeviceRetries + retryAttempt + 1;
                            long delay = RetryBackoff.computeDelay(
                                downloadProperties.getRetryDelayMs(),
                                downloadProperties.getRetryMaxDelayMs(),
                                retryOrdinal,
                                RETRY_MAX_BACKOFF_EXPONENT,
                                RETRY_JITTER_MIN_MS,
                                RETRY_JITTER_MAX_MS
                            );
                            RetryBackoff.sleep(delay);
                            candidate = performSearchInternal(firstRequest);
                        }

                        searchId = extractSearchId(candidate);
                        if (Texts.hasText(searchId)) {
                            break searchRetry;
                        }
                        if (hasBooks(candidate)) {
                            log.info("第一阶段未返回search_id，但重试后已返回书籍结果，跳过第二阶段");
                            autoRestartService.recordSuccess();
                            return candidate;
                        }
                    }
                }
            }
            if (Texts.isBlank(searchId)) {
                if (shouldLogHighFreq("search.no_search_id")) {
                    log.warn("第一阶段搜索未返回search_id（可能风控/上游异常），建议稍后重试");
                }
                autoRestartService.recordFailure(REASON_SEARCH_NO_SEARCH_ID);
                return FQNovelResponse.error(ERROR_NO_SEARCH_ID);
            }

            int lastSearchPageInterval;
            try {
                long delay = ThreadLocalRandom.current().nextLong(
                    FQConstants.Search.MIN_SEARCH_DELAY_MS,
                    FQConstants.Search.MAX_SEARCH_DELAY_MS + 1
                );
                Thread.sleep(delay);
                lastSearchPageInterval = (int) delay;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                autoRestartService.recordFailure(REASON_SEARCH_EXCEPTION);
                return FQNovelResponse.error("搜索流程被中断，请稍后重试");
            }

            FQSearchRequest secondRequest = copyRequest(enrichedRequest);
            secondRequest.setSearchId(searchId);
            secondRequest.setIsFirstEnterSearch(false);
            secondRequest.setLastSearchPageInterval(lastSearchPageInterval);
            FQNovelResponse<FQSearchResponse> secondResponse = performSearchInternal(secondRequest);
            if (secondResponse.getCode() == 0 && secondResponse.getData() != null) {
                secondResponse.getData().setSearchId(searchId);
            }
            recordSearchOutcome(secondResponse, REASON_SEARCH_PHASE2_FAIL);
            return secondResponse;

        } catch (Exception e) {
            String query = searchRequest == null ? null : searchRequest.getQuery();
            log.error("增强搜索失败 - query: {}", query, e);
            autoRestartService.recordFailure(REASON_SEARCH_EXCEPTION);
            return FQNovelResponse.error("增强搜索失败: " + e.getMessage());
        }
    }

    private static void ensurePassback(FQSearchRequest request) {
        if (request != null && request.getPassback() == null) {
            request.setPassback(request.getOffset());
        }
    }

    private static FQSearchRequest copyRequest(FQSearchRequest request) {
        FQSearchRequest copied = new FQSearchRequest();
        BeanUtils.copyProperties(request, copied);
        ensurePassback(copied);
        return copied;
    }

    private FQNovelResponse<FQSearchResponse> performSearchInternal(FQSearchRequest searchRequest) {
        try {
            FqVariable var = new FqVariable(fqApiProperties);
            String url = fqApiUtils.getSearchApiBaseUrl() + FQConstants.Search.TAB_PATH;
            Map<String, String> params = fqApiUtils.buildSearchParams(var, searchRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            UpstreamSignedRequestService.UpstreamJsonResult upstream = executeSignedJsonGetWithFailureLog(
                fullUrl,
                fqApiUtils.buildSearchHeaders(),
                "请求"
            );
            if (upstream == null) {
                return signerFailResponse();
            }

            ResponseEntity<byte[]> response = upstream.getResponse();
            String responseBody = upstream.getResponseBody();
            JsonNode jsonResponse = upstream.getJsonBody();

            Integer upstreamCode = nonZeroUpstreamCode(jsonResponse);
            if (upstreamCode != null) {
                String upstreamMessage = upstreamMessageOrDefault(jsonResponse, "upstream error");
                log.warn("上游搜索接口返回失败 - code: {}, message: {}", upstreamCode, upstreamMessage);
                return FQNovelResponse.error(upstreamCode, upstreamMessage);
            }

            int tabType = intOrDefault(searchRequest.getTabType(), 1);
            FQSearchResponse searchResponse = FQSearchResponseParser.parseSearchResponse(jsonResponse, tabType);
            if (searchResponse == null) {
                logUpstreamBodyDebug("搜索接口解析失败原始响应", responseBody);
                return FQNovelResponse.error("搜索响应解析失败");
            }

            if (Texts.isBlank(searchResponse.getSearchId())) {
                String fromBody = FQSearchResponseParser.deepFindSearchId(jsonResponse);
                if (Texts.hasText(fromBody)) {
                    searchResponse.setSearchId(fromBody);
                }
                if (Texts.isBlank(searchResponse.getSearchId())) {
                    String fromHeader = response == null ? "" : Texts.firstNonBlank(
                        response.getHeaders().getFirst("search_id"),
                        response.getHeaders().getFirst("search-id"),
                        response.getHeaders().getFirst("x-search-id"),
                        response.getHeaders().getFirst("x-fq-search-id")
                    );
                    if (Texts.hasText(fromHeader)) {
                        searchResponse.setSearchId(fromHeader);
                    }
                }
            }
            if (searchRequest != null
                && Boolean.TRUE.equals(searchRequest.getIsFirstEnterSearch())
                && Texts.isBlank(searchResponse.getSearchId())
                && log.isDebugEnabled()) {
                log.debug("第一阶段搜索未返回search_id，原始响应: {}",
                    Texts.truncate(responseBody, SEARCH_ID_DEBUG_SNIPPET_LENGTH));
            }

            return FQNovelResponse.success(searchResponse);

        } catch (Exception e) {
            log.error("搜索请求失败 - query: {}", searchRequest == null ? null : searchRequest.getQuery(), e);
            return FQNovelResponse.error("搜索请求失败: " + e.getMessage());
        }
    }

    public CompletableFuture<FQNovelResponse<FQDirectoryResponse>> getBookDirectory(FQDirectoryRequest directoryRequest) {
        CompletableFuture<FQNovelResponse<FQDirectoryResponse>> shuttingDown = completedShuttingDownIfNeeded();
        if (shuttingDown != null) {
            return shuttingDown;
        }

        String cacheKey = buildDirectoryCacheKey(directoryRequest);
        return loadWithRequestCache(
            cacheKey,
            directoryApiCache,
            inflightDirectory,
            () -> getBookDirectoryInternal(directoryRequest),
            FQSearchService::isResponseSuccessWithData,
            null,
            "目录"
        );
    }

    private FQNovelResponse<FQDirectoryResponse> getBookDirectoryInternal(FQDirectoryRequest directoryRequest) {
        try {
            FQNovelResponse<FQDirectoryResponse> shuttingDown = shuttingDownIfNeeded();
            if (shuttingDown != null) {
                return shuttingDown;
            }

            if (directoryRequest == null) {
                return FQNovelResponse.error("目录请求不能为空");
            }

            FqVariable var = new FqVariable(fqApiProperties);
            String url = fqApiUtils.getSearchApiBaseUrl() + FQConstants.Search.DIRECTORY_ALL_ITEMS_PATH;
            Map<String, String> params = fqApiUtils.buildDirectoryParams(var, directoryRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            UpstreamSignedRequestService.UpstreamJsonResult upstream = executeSignedJsonGetWithFailureLog(
                fullUrl,
                fqApiUtils.buildCommonHeaders(),
                "目录请求"
            );
            if (upstream == null) {
                return signerFailResponse();
            }

            String responseBody = upstream.getResponseBody();
            JsonNode rootNode = upstream.getJsonBody();
            Integer upstreamCode = nonZeroUpstreamCode(rootNode);
            if (upstreamCode != null) {
                String upstreamMessage = upstreamMessageOrDefault(rootNode, "upstream error");
                logUpstreamBodyDebug("目录接口上游失败原始响应", responseBody);
                return FQNovelResponse.error(upstreamCode, upstreamMessage);
            }

            JsonNode dataNode = rootNode.path("data");
            if (dataNode.isMissingNode() || dataNode.isNull()) {
                logUpstreamBodyDebug("目录接口上游缺少data原始响应", responseBody);
                return directoryFailure(upstreamMessageOrDefault(rootNode, "upstream response missing data"));
            }

            FQDirectoryResponse directoryResponse = objectMapper.treeToValue(dataNode, FQDirectoryResponse.class);
            if (directoryResponse == null) {
                return directoryFailure(upstreamMessageOrDefault(rootNode, "upstream parse error"));
            }
            if (Boolean.TRUE.equals(directoryRequest.getMinimalResponse())) {
                FQDirectoryResponseTransformer.trimForMinimalResponse(directoryResponse);
            } else {
                FQDirectoryResponseTransformer.enhanceChapterList(directoryResponse);
            }
            return FQNovelResponse.success(directoryResponse);

        } catch (Exception e) {
            String bookId = directoryRequest == null ? null : directoryRequest.getBookId();
            log.error("获取书籍目录失败 - bookId: {}", bookId, e);
            return directoryFailure(e.getMessage());
        }
    }

}
