package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.constants.FQConstants;
import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.anjia.unidbgserver.utils.GzipUtils;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.net.URI;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

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

    private Map<String, String> buildSearchHeaders() {
        Map<String, String> base = fqApiUtils.buildCommonHeaders();
        if (base.containsKey("authorization")) {
            return base;
        }

        // 尽量保持 header 插入顺序与抓包样例一致（authorization 放在 x-reading-request 后）
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : base.entrySet()) {
            ordered.put(entry.getKey(), entry.getValue());
            if ("x-reading-request".equalsIgnoreCase(entry.getKey())) {
                ordered.put("authorization", "Bearer");
            }
        }
        if (!ordered.containsKey("authorization")) {
            ordered.put("authorization", "Bearer");
        }
        return ordered;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isBlank(value)) return value;
        }
        return "";
    }

    private static String snippet(String value, int maxLen) {
        if (value == null) return "";
        if (value.length() <= maxLen) return value;
        return value.substring(0, Math.max(0, maxLen)) + "...";
    }

    private static Boolean boolFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        String s = node.asText("").trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return false;
        }
        return null;
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
     * 尝试从任意层级提取 search_id（兼容不同响应结构）。
     */
    private static String deepFindSearchId(JsonNode root) {
        if (root == null) {
            return "";
        }

        // 常见字段：search_id / searchId / search_id_str
        String direct = firstNonBlank(
            root.path("search_id").asText(""),
            root.path("searchId").asText(""),
            root.path("search_id_str").asText("")
        );
        if (!isBlank(direct)) {
            return direct;
        }

        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node == null) continue;

            if (node.isObject()) {
                String found = firstNonBlank(
                    node.path("search_id").asText(""),
                    node.path("searchId").asText(""),
                    node.path("search_id_str").asText("")
                );
                if (!isBlank(found)) {
                    return found;
                }
                node.fields().forEachRemaining(e -> stack.push(e.getValue()));
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    stack.push(child);
                }
            }
        }

        return "";
    }

    /**
     * 获取默认FQ变量（延迟初始化）
     */
    private FqVariable getDefaultFqVariable() {
        return new FqVariable(fqApiProperties);
    }

    /**
     * 搜索书籍 - 增强版，支持两阶段搜索
     *
     * @param searchRequest 搜索请求参数
     * @return 搜索结果
     */
    public CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooksEnhanced(FQSearchRequest searchRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (ProcessLifecycle.isShuttingDown()) {
                    return FQNovelResponse.error("服务正在退出中，请稍后重试");
                }

                searchRequestEnricher.enrich(searchRequest);

                // 如果用户已经提供了search_id，直接进行搜索
                if (searchRequest.getSearchId() != null && !searchRequest.getSearchId().trim().isEmpty()) {
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
                if (isBlank(firstSearchId)) {
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
                            if (!isBlank(candidateSearchId)) {
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

                        if (!isBlank(firstSearchId)) {
                            break;
                        }
                    }

                    if (isBlank(firstSearchId)) {
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
                if (secondResponse.getCode() == 0 && secondResponse.getData() != null ){
                    secondResponse.getData().setSearchId(searchId);
                }

                if (secondResponse != null && secondResponse.getCode() != null && secondResponse.getCode() == 0) {
                    autoRestartService.recordSuccess();
                } else {
                    autoRestartService.recordFailure("SEARCH_PHASE2_FAIL");
                }
                return secondResponse;

            } catch (Exception e) {
                log.error("增强搜索失败 - query: {}", searchRequest.getQuery(), e);
                autoRestartService.recordFailure("SEARCH_EXCEPTION");
                return FQNovelResponse.error("增强搜索失败: " + e.getMessage());
            }
        }, taskExecutor);
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
        if (firstRequest.getPassback() == null) {
            firstRequest.setPassback(firstRequest.getOffset());
        }

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
        if (secondRequest.getPassback() == null) {
            secondRequest.setPassback(secondRequest.getOffset());
        }

        return secondRequest;
    }

    /**
     * 复制基本参数
     */
    private void copyBasicParameters(FQSearchRequest source, FQSearchRequest target) {
        target.setQuery(source.getQuery());
        target.setOffset(source.getOffset());
        target.setCount(source.getCount());
        target.setTabType(source.getTabType());
        target.setPassback(source.getPassback());
        target.setBookshelfSearchPlan(source.getBookshelfSearchPlan());
        target.setFromRs(source.getFromRs());
        target.setUserIsLogin(source.getUserIsLogin());
        target.setBookstoreTab(source.getBookstoreTab());
        target.setSearchSource(source.getSearchSource());
        target.setClickedContent(source.getClickedContent());
        target.setSearchSourceId(source.getSearchSourceId());
        target.setUseLynx(source.getUseLynx());
        target.setUseCorrect(source.getUseCorrect());
        target.setTabName(source.getTabName());
        target.setClientAbInfo(source.getClientAbInfo());
        target.setLineWordsNum(source.getLineWordsNum());
        target.setLastConsumeInterval(source.getLastConsumeInterval());
        target.setPadColumnCover(source.getPadColumnCover());
        target.setKlinkEgdi(source.getKlinkEgdi());
        target.setNormalSessionId(source.getNormalSessionId());
        target.setColdStartSessionId(source.getColdStartSessionId());
        target.setCharging(source.getCharging());
        target.setScreenBrightness(source.getScreenBrightness());
        target.setBatteryPct(source.getBatteryPct());
        target.setDownSpeed(source.getDownSpeed());
        target.setSysDarkMode(source.getSysDarkMode());
        target.setAppDarkMode(source.getAppDarkMode());
        target.setFontScale(source.getFontScale());
        target.setIsAndroidPadScreen(source.getIsAndroidPadScreen());
        target.setNetworkType(source.getNetworkType());
        target.setRomVersion(source.getRomVersion());
        target.setCurrentVolume(source.getCurrentVolume());
        target.setCdid(source.getCdid());
        target.setNeedPersonalRecommend(source.getNeedPersonalRecommend());
        target.setPlayerSoLoad(source.getPlayerSoLoad());
        target.setGender(source.getGender());
        target.setComplianceStatus(source.getComplianceStatus());
        target.setHarStatus(source.getHarStatus());
    }

    /**
     * 执行带search_id的搜索
     */
    private FQNovelResponse<FQSearchResponse> performSearchWithId(FQSearchRequest searchRequest) {
        // 确保is_first_enter_search为false，不包含client_ab_info
        searchRequest.setIsFirstEnterSearch(false);

        searchRequestEnricher.enrich(searchRequest);

        // 确保passback与offset相同
        if (searchRequest.getPassback() == null) {
            searchRequest.setPassback(searchRequest.getOffset());
        }

        return performSearchInternal(searchRequest);
    }

    /**
     * 执行实际的搜索请求
     */
    private FQNovelResponse<FQSearchResponse> performSearchInternal(FQSearchRequest searchRequest) {
        try {
            FqVariable var = getDefaultFqVariable();

            // 构建搜索URL和参数
            String url = fqApiUtils.getBaseUrl().replace("api5-normal-sinfonlineb", "api5-normal-sinfonlinec")
                + "/reading/bookapi/search/tab/v";
            Map<String, String> params = fqApiUtils.buildSearchParams(var, searchRequest);
            String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

            // 构建请求头
            Map<String, String> headers = buildSearchHeaders();

            // 生成签名
            Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeaders(fullUrl, headers).get();
            if (signedHeaders == null || signedHeaders.isEmpty()) {
                log.error("签名生成失败，终止请求 - url: {}", fullUrl);
                return FQNovelResponse.error("签名生成失败");
            }

            // 发起API请求
            HttpHeaders httpHeaders = new HttpHeaders();
            headers.forEach(httpHeaders::set);
            signedHeaders.forEach(httpHeaders::set);

            HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
            URI uri = URI.create(fullUrl);

            upstreamRateLimiter.acquire();
            ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class);

            // 解压缩 GZIP 响应体
            String responseBody = GzipUtils.decompressGzipResponse(response.getBody());

            // 解析响应
            JsonNode jsonResponse = objectMapper.readTree(responseBody);

            // 上游如果有 code/message，优先按其判断是否成功
            if (jsonResponse.has("code")) {
                int upstreamCode = jsonResponse.path("code").asInt(0);
                if (upstreamCode != 0) {
                    String upstreamMessage = jsonResponse.path("message").asText("upstream error");
                    log.warn("上游搜索接口返回失败 - code: {}, message: {}", upstreamCode, upstreamMessage);
                    return FQNovelResponse.error(upstreamCode, upstreamMessage);
                }
            }

            int tabType = searchRequest.getTabType(); // 从请求获取需要的tab_type
            FQSearchResponse searchResponse = parseSearchResponse(jsonResponse, tabType);

            // 兜底：如果 parseSearchResponse 没取到 search_id，再做一次深度提取（含 root/data/log_pb 等）
            if (searchResponse != null && isBlank(searchResponse.getSearchId())) {
                String fromBody = deepFindSearchId(jsonResponse);
                if (!isBlank(fromBody)) {
                    searchResponse.setSearchId(fromBody);
                }
            }

            // 兜底：部分情况下 search_id 可能在响应头里
            if (searchResponse != null && isBlank(searchResponse.getSearchId())) {
                String fromHeader = firstNonBlank(
                    response.getHeaders().getFirst("search_id"),
                    response.getHeaders().getFirst("search-id"),
                    response.getHeaders().getFirst("x-search-id"),
                    response.getHeaders().getFirst("x-fq-search-id")
                );
                if (!isBlank(fromHeader)) {
                    searchResponse.setSearchId(fromHeader);
                }
            }

            if (Boolean.TRUE.equals(searchRequest.getIsFirstEnterSearch())
                && (searchResponse == null || isBlank(searchResponse.getSearchId()))
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
        if (isBlank(message)) {
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
     * 搜索书籍
     *
     * @param searchRequest 搜索请求参数
     * @return 搜索结果
     */
    public CompletableFuture<FQNovelResponse<FQSearchResponse>> searchBooks(FQSearchRequest searchRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (ProcessLifecycle.isShuttingDown()) {
                    return FQNovelResponse.error("服务正在退出中，请稍后重试");
                }

                searchRequestEnricher.enrich(searchRequest);

                FqVariable var = getDefaultFqVariable();

                // 构建搜索URL和参数
                String url = fqApiUtils.getBaseUrl().replace("api5-normal-sinfonlineb", "api5-normal-sinfonlinec")
                    + "/reading/bookapi/search/tab/v";
                Map<String, String> params = fqApiUtils.buildSearchParams(var, searchRequest);
                String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

                // 构建请求头
                Map<String, String> headers = buildSearchHeaders();

                // 生成签名
                Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeaders(fullUrl, headers).get();

                // 发起API请求
                HttpHeaders httpHeaders = new HttpHeaders();
                headers.forEach(httpHeaders::set);
                signedHeaders.forEach(httpHeaders::set);

                HttpEntity<String> entity = new HttpEntity<>(httpHeaders);

                URI uri = URI.create(fullUrl);

                upstreamRateLimiter.acquire();
                ResponseEntity<byte[]> response = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class);

                // 解压缩 GZIP 响应体
                String responseBody = GzipUtils.decompressGzipResponse(response.getBody());

                // 解析响应
                JsonNode jsonResponse = objectMapper.readTree(responseBody);

                int tabType = searchRequest.getTabType(); // 从请求获取需要的tab_type
                FQSearchResponse searchResponse = parseSearchResponse(jsonResponse,tabType);

                autoRestartService.recordSuccess();
                return FQNovelResponse.success(searchResponse);

            } catch (Exception e) {
                log.error("搜索书籍失败 - query: {}", searchRequest.getQuery(), e);
                autoRestartService.recordFailure("SEARCH_SIMPLE_EXCEPTION");
                return FQNovelResponse.error("搜索书籍失败: " + e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 获取书籍目录（增强版）
     *
     * @param directoryRequest 目录请求参数
     * @return 书籍目录
     */
    public CompletableFuture<FQNovelResponse<FQDirectoryResponse>> getBookDirectory(FQDirectoryRequest directoryRequest) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (ProcessLifecycle.isShuttingDown()) {
                    return FQNovelResponse.error("服务正在退出中，请稍后重试");
                }

                FqVariable var = getDefaultFqVariable();

                // 构建目录URL和参数
                String url = fqApiUtils.getBaseUrl().replace("api5-normal-sinfonlineb", "api5-normal-sinfonlinec")
                    + "/reading/bookapi/directory/all_items/v";
                Map<String, String> params = fqApiUtils.buildDirectoryParams(var, directoryRequest);
                String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

                // 构建请求头
                Map<String, String> headers = fqApiUtils.buildCommonHeaders();

                // 生成签名
                Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeaders(fullUrl, headers).get();
                if (signedHeaders == null || signedHeaders.isEmpty()) {
                    log.error("签名生成失败，终止目录请求 - url: {}", fullUrl);
                    return FQNovelResponse.error("签名生成失败");
                }

                // 发起API请求
                HttpHeaders httpHeaders = new HttpHeaders();
                headers.forEach(httpHeaders::set);
                signedHeaders.forEach(httpHeaders::set);

                HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
                upstreamRateLimiter.acquire();
                ResponseEntity<byte[]> response = restTemplate.exchange(fullUrl, HttpMethod.GET, entity, byte[].class);

                // 解压缩 GZIP 响应体
                String responseBody = GzipUtils.decompressGzipResponse(response.getBody());

                JsonNode rootNode = objectMapper.readTree(responseBody);
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

                // 增强章节列表数据
                enhanceChapterList(directoryResponse);

                return FQNovelResponse.success(directoryResponse);

            } catch (Exception e) {
                log.error("获取书籍目录失败 - bookId: {}", directoryRequest.getBookId(), e);
                return FQNovelResponse.error("获取书籍目录失败: " + e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 增强章节列表数据
     * 添加章节序号、格式化时间、最新章节标记等
     *
     * @param directoryResponse 目录响应对象
     */
    private void enhanceChapterList(FQDirectoryResponse directoryResponse) {
        if (directoryResponse == null || directoryResponse.getItemDataList() == null) {
            return;
        }

        List<FQDirectoryResponse.ItemData> itemDataList = directoryResponse.getItemDataList();
        int totalChapters = itemDataList.size();

        for (int i = 0; i < totalChapters; i++) {
            FQDirectoryResponse.ItemData item = itemDataList.get(i);
            
            // 设置章节序号（从1开始）
            item.setChapterIndex(i + 1);
            
            // 标记最新章节
            item.setIsLatest(i == totalChapters - 1);
            
            // 格式化首次通过时间
            if (item.getFirstPassTime() != null && item.getFirstPassTime() > 0) {
                try {
                    long timestamp = item.getFirstPassTime() * 1000L; // 转换为毫秒
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    item.setFirstPassTimeStr(sdf.format(new java.util.Date(timestamp)));
                } catch (Exception e) {
                    log.warn("格式化时间失败 - timestamp: {}", item.getFirstPassTime(), e);
                }
            }
            
            // 设置排序序号（与序号相同）
            if (item.getSortOrder() == null) {
                item.setSortOrder(i + 1);
            }
            
            if (item.getIsFree() == null) {
                item.setIsFree(i < 5);
            }
        }
        
        log.debug("章节列表增强完成 - 总章节数: {}", totalChapters);
    }

    /**
     * 解析搜索响应，根据 tabType 提取内容
     */
    public static FQSearchResponse parseSearchResponse(JsonNode jsonResponse, int tabType) {
        FQSearchResponse searchResponse = new FQSearchResponse();

        // 兼容两种结构：
        // 1) { "search_tabs": [...] }
        // 2) { "code":0, "data": { "search_tabs": [...] } }
        JsonNode dataNode = jsonResponse != null ? jsonResponse.path("data") : null;
        JsonNode searchTabs = jsonResponse != null ? jsonResponse.get("search_tabs") : null;
        if (searchTabs == null || !searchTabs.isArray()) {
            searchTabs = dataNode != null ? dataNode.get("search_tabs") : null;
        }
        // 兼容驼峰：searchTabs
        if (searchTabs == null || !searchTabs.isArray()) {
            searchTabs = jsonResponse != null ? jsonResponse.get("searchTabs") : null;
        }
        if (searchTabs == null || !searchTabs.isArray()) {
            searchTabs = dataNode != null ? dataNode.get("searchTabs") : null;
        }

        // search_tabs 是数组
        boolean matchedTab = false;
        if (searchTabs != null && searchTabs.isArray()) {
            for (JsonNode tab : searchTabs) {
                if (tab.has("tab_type") && tab.get("tab_type").asInt() == tabType) {
                    matchedTab = true;
                    List<FQSearchResponse.BookItem> books = new ArrayList<>();
                    JsonNode tabData = tab.get("data");
                    if (tabData != null && tabData.isArray()) {
                        for (JsonNode cell : tabData) {
                            JsonNode bookData = cell.get("book_data");
                            if (bookData != null && bookData.isArray()) {
                                for (JsonNode bookNode : bookData) {
                                    books.add(parseBookItem(bookNode));
                                }
                            }
                        }
                    }
                    // 某些场景下可能直接返回 tab.books
                    JsonNode directBooks = tab.get("books");
                    if ((books == null || books.isEmpty()) && directBooks != null && directBooks.isArray()) {
                        for (JsonNode bookNode : directBooks) {
                            books.add(parseBookItem(bookNode));
                        }
                    }
                    searchResponse.setBooks(books);

                    // 解析 tab 的其他字段
                    searchResponse.setTotal(tab.path("total").asInt(books.size())); // 若没有 total 字段则用 books.size
                    searchResponse.setHasMore(tab.path("has_more").asBoolean(false));
                    String tabSearchId = tab.path("search_id").asText("");
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = tab.path("searchId").asText("");
                    }
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = tab.path("search_id_str").asText("");
                    }
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = dataNode != null ? dataNode.path("search_id").asText("") : "";
                    }
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = dataNode != null ? dataNode.path("searchId").asText("") : "";
                    }
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = dataNode != null ? dataNode.path("search_id_str").asText("") : "";
                    }
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = jsonResponse != null ? jsonResponse.path("search_id").asText("") : "";
                    }
                    if (tabSearchId == null || tabSearchId.isEmpty()) {
                        tabSearchId = jsonResponse != null ? jsonResponse.path("searchId").asText("") : "";
                    }
                    searchResponse.setSearchId(tabSearchId);

                    break;
                }
            }
        }

        // 兜底：如果没有匹配到 tabType，但 tabs 中存在可用书籍数据，优先取第一个有书籍的 tab，避免误判为“无结果/风控”
        if (!matchedTab && (searchResponse.getBooks() == null || searchResponse.getBooks().isEmpty()) && searchTabs != null && searchTabs.isArray()) {
            for (JsonNode tab : searchTabs) {
                List<FQSearchResponse.BookItem> books = new ArrayList<>();

                JsonNode tabData = tab.get("data");
                if (tabData != null && tabData.isArray()) {
                    for (JsonNode cell : tabData) {
                        JsonNode bookData = cell.get("book_data");
                        if (bookData != null && bookData.isArray()) {
                            for (JsonNode bookNode : bookData) {
                                books.add(parseBookItem(bookNode));
                            }
                        }
                    }
                }
                JsonNode directBooks = tab.get("books");
                if (books.isEmpty() && directBooks != null && directBooks.isArray()) {
                    for (JsonNode bookNode : directBooks) {
                        books.add(parseBookItem(bookNode));
                    }
                }

                if (!books.isEmpty()) {
                    searchResponse.setBooks(books);
                    if (searchResponse.getTotal() == null) {
                        searchResponse.setTotal(tab.path("total").asInt(books.size()));
                    }
                    if (searchResponse.getHasMore() == null) {
                        Boolean hm = boolFromNode(tab.path("has_more"));
                        searchResponse.setHasMore(Boolean.TRUE.equals(hm));
                    }
                    if (isBlank(searchResponse.getSearchId())) {
                        String tabSearchId = firstNonBlank(
                            tab.path("search_id").asText(""),
                            tab.path("searchId").asText(""),
                            tab.path("search_id_str").asText("")
                        );
                        searchResponse.setSearchId(tabSearchId);
                    }
                    break;
                }
            }
        }

        // 兜底：部分接口/场景可能直接返回 data.books（没有 search_tabs），避免解析不到 books 导致误判为“无结果/风控”
        if (searchResponse.getBooks() == null || searchResponse.getBooks().isEmpty()) {
            JsonNode booksNode = null;
            if (dataNode != null && dataNode.path("books").isArray()) {
                booksNode = dataNode.path("books");
            } else if (jsonResponse != null && jsonResponse.path("books").isArray()) {
                booksNode = jsonResponse.path("books");
            }

            if (booksNode != null && booksNode.isArray()) {
                List<FQSearchResponse.BookItem> books = new ArrayList<>();
                for (JsonNode bookNode : booksNode) {
                    books.add(parseBookItem(bookNode));
                }
                searchResponse.setBooks(books);

                if (searchResponse.getTotal() == null) {
                    int total = dataNode != null ? dataNode.path("total").asInt(books.size()) : books.size();
                    searchResponse.setTotal(total);
                }
                if (searchResponse.getHasMore() == null) {
                    Boolean hasMore = null;
                    if (dataNode != null) {
                        hasMore = boolFromNode(dataNode.path("has_more"));
                        if (hasMore == null) {
                            hasMore = boolFromNode(dataNode.path("hasMore"));
                        }
                    }
                    searchResponse.setHasMore(Boolean.TRUE.equals(hasMore));
                }
            }
        }

        // 如果没命中 tab，也尽量把 search_id 填上（便于外部继续翻页）
        if (isBlank(searchResponse.getSearchId())) {
            String fallback = firstNonBlank(
                dataNode != null ? dataNode.path("search_id").asText("") : "",
                dataNode != null ? dataNode.path("searchId").asText("") : "",
                jsonResponse != null ? jsonResponse.path("search_id").asText("") : "",
                jsonResponse != null ? jsonResponse.path("searchId").asText("") : ""
            );
            searchResponse.setSearchId(fallback);
        }
        return searchResponse;
    }

    /**
     * 解析书籍项目，字段映射按实际API返回（完整映射）
     */
    private static FQSearchResponse.BookItem parseBookItem(JsonNode bookNode) {
        FQSearchResponse.BookItem book = new FQSearchResponse.BookItem();

        // ============ 基础信息 ============
        book.setBookId(bookNode.path("book_id").asText(""));
        book.setBookName(bookNode.path("book_name").asText(""));
        book.setBookShortName(bookNode.path("book_short_name").asText(""));
        book.setAuthor(bookNode.path("author").asText(""));
        book.setAuthorId(bookNode.path("author_id").asText(""));
        
        // 作者信息
        JsonNode authorInfoNode = bookNode.path("author_info");
        if (authorInfoNode != null && !authorInfoNode.isMissingNode() && authorInfoNode.isObject()) {
            Map<String, Object> authorInfoMap = new HashMap<>();
            authorInfoNode.fields().forEachRemaining(entry -> {
                authorInfoMap.put(entry.getKey(), entry.getValue());
            });
            book.setAuthorInfo(authorInfoMap);
        }
        
        book.setDescription(bookNode.path("abstract").asText(""));
        book.setBookAbstractV2(bookNode.path("book_abstract_v2").asText(""));
        book.setCoverUrl(bookNode.path("thumb_url").asText(""));
        book.setDetailPageThumbUrl(bookNode.path("detail_page_thumb_url").asText(""));
        book.setExpandThumbUrl(bookNode.path("expand_thumb_url").asText(""));
        book.setHorizThumbUrl(bookNode.path("horiz_thumb_url").asText(""));
        book.setStatus(bookNode.path("status").asInt(0));
        book.setCreationStatus(bookNode.path("creation_status").asText(""));
        book.setUpdateStatus(bookNode.path("update_status").asText(""));
        
        // ============ 章节信息 ============
        book.setWordCount(bookNode.path("word_number").asLong(0));
        
        // 尝试从不同字段获取章节总数
        if (bookNode.has("serial_count")) {
            book.setTotalChapters(bookNode.path("serial_count").asInt(0));
        } else if (bookNode.has("content_chapter_number")) {
            book.setTotalChapters(bookNode.path("content_chapter_number").asInt(0));
        }
        
        book.setFirstChapterTitle(bookNode.path("first_chapter_title").asText(""));
        book.setFirstChapterItemId(bookNode.path("first_chapter_item_id").asText(""));
        book.setLastChapterTitle(bookNode.path("last_chapter_title").asText(""));
        book.setLastChapterItemId(bookNode.path("last_chapter_item_id").asText(""));
        book.setUpdateTime(bookNode.path("last_chapter_update_time").asLong(0));
        book.setLastChapterUpdateTime(bookNode.path("last_chapter_update_time").asText(""));
        
        // ============ 分类信息 ============
        book.setCategory(bookNode.path("category").asText(""));
        book.setCategoryV2(bookNode.path("category_v2").asText(""));
        book.setCompleteCategory(bookNode.path("complete_category").asText(""));
        book.setGenre(bookNode.path("genre").asText(""));
        book.setSubGenre(bookNode.path("sub_genre").asText(""));
        book.setGender(bookNode.path("gender").asText(""));
        
        // 标签兼容逗号分隔字符串和数组
        JsonNode tagsNode = bookNode.path("tags");
        if (tagsNode.isArray()) {
            List<String> tags = new ArrayList<>();
            for (JsonNode tag : tagsNode) {
                tags.add(tag.asText());
            }
            book.setTags(tags);
            book.setTagsStr(String.join(",", tags));
        } else {
            String tagsStr = tagsNode.asText("");
            if (!tagsStr.isEmpty()) {
                book.setTags(Arrays.asList(tagsStr.split(",")));
                book.setTagsStr(tagsStr);
            }
        }
        
        // ============ 统计数据 ============
        book.setRating(bookNode.path("score").asDouble(0.0));
        book.setReadCount(bookNode.path("read_count").asLong(0L));
        book.setReadCntText(bookNode.path("read_cnt_text").asText(""));
        book.setAddBookshelfCount(bookNode.path("add_bookshelf_count").asLong(0L));
        book.setReaderUv14day(bookNode.path("reader_uv_14day").asLong(0L));
        book.setListenCount(bookNode.path("listen_count").asLong(0L));
        book.setFinishRate10(bookNode.path("finish_rate_10").asDouble(0.0));
        
        // ============ 价格与销售 ============
        book.setTotalPrice(bookNode.path("total_price").asLong(0L));
        book.setBasePrice(bookNode.path("base_price").asLong(0L));
        book.setDiscountPrice(bookNode.path("discount_price").asLong(0L));
        book.setFreeStatus(bookNode.path("free_status").asText(""));
        book.setVipBook(bookNode.path("vip_book").asText(""));
        
        // ============ 授权与版权 ============
        book.setExclusive(bookNode.path("exclusive").asText(""));
        book.setRealExclusive(bookNode.path("real_exclusive").asText(""));
        book.setCopyrightInfo(bookNode.path("copyright_info").asText(""));
        
        // ============ 显示与颜色 ============
        book.setColorDominate(bookNode.path("color_dominate").asText(""));
        book.setColorMostPopular(bookNode.path("color_most_popular").asText(""));
        book.setThumbUri(bookNode.path("thumb_uri").asText(""));
        
	        // ============ 时间信息 ============
	        book.setCreateTime(bookNode.path("create_time").asLong(0L));
	        book.setPublishedDate(bookNode.path("published_date").asText(""));
	        book.setLastPublishTime(bookNode.path("last_publish_time").asLong(0L));
	        book.setFirstOnlineTime(bookNode.path("first_online_time").asLong(0L));
        
        // ============ 书籍类型 ============
        book.setBookType(bookNode.path("book_type").asText(""));
        book.setIsNew(boolFromNode(bookNode.path("is_new")));
        book.setIsEbook(boolFromNode(bookNode.path("is_ebook")));
        book.setLengthType(bookNode.path("length_type").asText(""));
        
        // ============ 其他信息 ============
        book.setBookSearchVisible(bookNode.path("book_search_visible").asText(""));
        book.setPress(bookNode.path("press").asText(""));
        book.setPublisher(bookNode.path("publisher").asText(""));
        book.setIsbn(bookNode.path("isbn").asText(""));
        book.setSource(bookNode.path("source").asText(""));
        book.setPlatform(bookNode.path("platform").asText(""));
        book.setFlightFlag(bookNode.path("flight_flag").asText(""));
	        book.setRecommendCountLevel(bookNode.path("recommend_count_level").asText(""));
	        book.setDataRate(bookNode.path("data_rate").asDouble(0.0));
	        book.setRiskRate(bookNode.path("risk_rate").asDouble(0.0));
	        
	        return book;
	    }

}
