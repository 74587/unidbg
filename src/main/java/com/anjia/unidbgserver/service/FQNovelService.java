package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.anjia.unidbgserver.utils.GzipUtils;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;

/**
 * FQNovel 小说内容获取服务
 * 基于 fqnovel-api 的 Rust 实现移植
 */
@Slf4j
@Service
public class FQNovelService {

    private static final String BATCH_FULL_PATH = "/reading/reader/batch_full/v";
    private static final String REASON_ILLEGAL_ACCESS = "ILLEGAL_ACCESS";
    private static final String REASON_UPSTREAM_EMPTY = "UPSTREAM_EMPTY";
    private static final String REASON_UPSTREAM_GZIP = "UPSTREAM_GZIP";
    private static final String REASON_UPSTREAM_NON_JSON = "UPSTREAM_NON_JSON";
    private static final String REASON_SIGNER_FAIL = "SIGNER_FAIL";

    private static final String EX_EMPTY_UPSTREAM_RESPONSE = "Empty upstream response";
    private static final String EX_NON_JSON_UPSTREAM_RESPONSE = "UPSTREAM_NON_JSON";
    private static final String EX_SIGNER_FAIL = "签名生成失败";
    private static final String EX_GZIP_NOT_IN_FORMAT = "Not in GZIP format";
    private static final String EX_JACKSON_EMPTY_CONTENT = "No content to map due to end-of-input";

    private static final long ILLEGAL_ACCESS_CODE = 110L;
    private static final int MAX_BACKOFF_EXPONENT = 10;
    private static final long RETRY_JITTER_MAX_MS = 250L;
    private static final int SIGNER_RESET_ON_EMPTY_ATTEMPT_THRESHOLD = 2;


    @Resource(name = "fqEncryptWorker")
    private FQEncryptServiceWorker fqEncryptServiceWorker;

    @Resource
    private FQApiProperties fqApiProperties;

    @Resource
    private FQApiUtils fqApiUtils;

    @Resource
    private FQSearchService fqSearchService;

    @Resource
    private UpstreamRateLimiter upstreamRateLimiter;

    @Resource
    private FQDownloadProperties downloadProperties;

    @Resource
    private FQDeviceRotationService deviceRotationService;

    @Resource
    private AutoRestartService autoRestartService;

    @Resource
    private RestTemplate restTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource(name = "applicationTaskExecutor")
    private Executor taskExecutor;

    /**
     * 获取默认FQ变量（延迟初始化）
     */
    private FqVariable getDefaultFqVariable() {
        // 设备信息可能在运行期被自动旋转；这里不做缓存，确保每次取到最新配置
        return new FqVariable(fqApiProperties);
    }

    /**
     * 获取章节内容 (基于 fqnovel-api 的 batch_full 方法)
     *
     * @param itemIds 章节ID列表，逗号分隔
     * @param bookId 书籍ID
     * @param download 是否下载模式 (false=在线阅读, true=下载)
     * @return 内容响应
     */
    public CompletableFuture<FQNovelResponse<FqIBatchFullResponse>> batchFull(String itemIds, String bookId, boolean download) {
        return CompletableFuture.supplyAsync(() -> executeBatchFullWithRetry(itemIds, bookId, download), taskExecutor);
    }

    private FQNovelResponse<FqIBatchFullResponse> executeBatchFullWithRetry(String itemIds, String bookId, boolean download) {
        if (ProcessLifecycle.isShuttingDown()) {
            return FQNovelResponse.error("服务正在退出中，请稍后重试");
        }

        int maxAttempts = Math.max(1, downloadProperties.getMaxRetries());
        long baseDelayMs = Math.max(0L, downloadProperties.getRetryDelayMs());
        long maxDelayMs = Math.max(baseDelayMs, downloadProperties.getRetryMaxDelayMs());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return fetchBatchFullOnce(itemIds, bookId, download);
            } catch (Exception e) {
                FQNovelResponse<FqIBatchFullResponse> decision =
                    handleBatchFullException(e, itemIds, attempt, maxAttempts, baseDelayMs, maxDelayMs);
                if (decision != null) {
                    return decision;
                }
            }
        }
        return FQNovelResponse.error("获取章节内容失败: 超过最大重试次数");
    }

    private FQNovelResponse<FqIBatchFullResponse> fetchBatchFullOnce(String itemIds, String bookId, boolean download) throws Exception {
        FqVariable var = getDefaultFqVariable();

        String url = fqApiUtils.getBaseUrl() + BATCH_FULL_PATH;
        Map<String, String> params = fqApiUtils.buildBatchFullParams(var, itemIds, bookId, download);
        String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

        Map<String, String> headers = fqApiUtils.buildCommonHeaders();

        upstreamRateLimiter.acquire();
        Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeadersSync(fullUrl, headers);
        if (signedHeaders == null || signedHeaders.isEmpty()) {
            throw new IllegalStateException(EX_SIGNER_FAIL);
        }

        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::set);
        signedHeaders.forEach(httpHeaders::set);

        HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
        ResponseEntity<byte[]> response = restTemplate.exchange(fullUrl, HttpMethod.GET, entity, byte[].class);

        String responseBody = GzipUtils.decodeUpstreamResponse(response);
        String trimmedBody = responseBody.trim();
        if (trimmedBody.isEmpty()) {
            throw new RuntimeException(EX_EMPTY_UPSTREAM_RESPONSE);
        }

        if (trimmedBody.startsWith("<")) {
            if (trimmedBody.contains(REASON_ILLEGAL_ACCESS)) {
                throw new IllegalStateException(REASON_ILLEGAL_ACCESS);
            }
            throw new IllegalStateException(EX_NON_JSON_UPSTREAM_RESPONSE);
        }
        if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
            if (trimmedBody.contains(REASON_ILLEGAL_ACCESS)) {
                throw new IllegalStateException(REASON_ILLEGAL_ACCESS);
            }
            throw new IllegalStateException(EX_NON_JSON_UPSTREAM_RESPONSE);
        }

        FqIBatchFullResponse batchResponse = objectMapper.readValue(responseBody, FqIBatchFullResponse.class);
        if (batchResponse == null) {
            throw new RuntimeException("Upstream parse failed");
        }

        if (batchResponse.getCode() != 0) {
            String msg = batchResponse.getMessage() != null ? batchResponse.getMessage() : "";
            if (isIllegalAccess(batchResponse.getCode(), msg, responseBody)) {
                throw new IllegalStateException(REASON_ILLEGAL_ACCESS);
            }
            return FQNovelResponse.error((int) batchResponse.getCode(), msg);
        }

        autoRestartService.recordSuccess();
        return FQNovelResponse.success(batchResponse);
    }

    private FQNovelResponse<FqIBatchFullResponse> handleBatchFullException(
        Exception e,
        String itemIds,
        int attempt,
        int maxAttempts,
        long baseDelayMs,
        long maxDelayMs
    ) {
        String message = e.getMessage() != null ? e.getMessage() : "";
        String retryReason = resolveRetryReason(message);
        boolean retryable = retryReason != null;

        if (!retryable || attempt >= maxAttempts) {
            if (retryable) {
                autoRestartService.recordFailure(retryReason);
            }
            return buildBatchFullFailureResponse(retryReason, message, itemIds, e);
        }

        if (REASON_SIGNER_FAIL.equals(retryReason)
            || (REASON_UPSTREAM_EMPTY.equals(retryReason) && attempt >= SIGNER_RESET_ON_EMPTY_ATTEMPT_THRESHOLD)) {
            FQEncryptServiceWorker.requestGlobalReset(retryReason);
        }
        // 所有可重试异常都遵循设备切换冷却，避免高并发时在设备池里来回抖动。
        deviceRotationService.rotateIfNeeded(retryReason);

        long delay = computeRetryDelay(baseDelayMs, maxDelayMs, attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return FQNovelResponse.error("获取章节内容失败: 重试被中断");
        }

        return null;
    }

    private static String resolveRetryReason(String message) {
        boolean illegal = message.contains(REASON_ILLEGAL_ACCESS);
        boolean empty = message.contains(EX_EMPTY_UPSTREAM_RESPONSE) || message.contains(EX_JACKSON_EMPTY_CONTENT);
        boolean gzipErr = message.contains(EX_GZIP_NOT_IN_FORMAT);
        boolean nonJson = message.contains(EX_NON_JSON_UPSTREAM_RESPONSE);
        boolean signerFail = message.contains(EX_SIGNER_FAIL);

        if (illegal) {
            return REASON_ILLEGAL_ACCESS;
        }
        if (empty) {
            return REASON_UPSTREAM_EMPTY;
        }
        if (gzipErr) {
            return REASON_UPSTREAM_GZIP;
        }
        if (nonJson) {
            return REASON_UPSTREAM_NON_JSON;
        }
        if (signerFail) {
            return REASON_SIGNER_FAIL;
        }
        return null;
    }

    private static long computeRetryDelay(long baseDelayMs, long maxDelayMs, int attempt) {
        long delay = baseDelayMs <= 0
            ? 0L
            : baseDelayMs * (1L << Math.min(MAX_BACKOFF_EXPONENT, Math.max(0, attempt - 1)));
        delay = Math.min(delay, maxDelayMs);
        delay += ThreadLocalRandom.current().nextLong(0, RETRY_JITTER_MAX_MS);
        return delay;
    }

    private FQNovelResponse<FqIBatchFullResponse> buildBatchFullFailureResponse(
        String retryReason,
        String message,
        String itemIds,
        Exception e
    ) {
        if (REASON_ILLEGAL_ACCESS.equals(retryReason)) {
            return FQNovelResponse.error("获取章节内容失败: ILLEGAL_ACCESS（已重试仍失败，建议更换设备/降低频率）");
        }
        if (REASON_UPSTREAM_GZIP.equals(retryReason)) {
            return FQNovelResponse.error("获取章节内容失败: 响应格式异常（已重试仍失败）");
        }
        if (REASON_UPSTREAM_NON_JSON.equals(retryReason)) {
            return FQNovelResponse.error("获取章节内容失败: 上游返回非JSON（已重试仍失败）");
        }
        if (REASON_UPSTREAM_EMPTY.equals(retryReason)) {
            return FQNovelResponse.error("获取章节内容失败: 空响应（已重试仍失败）");
        }
        if (REASON_SIGNER_FAIL.equals(retryReason)) {
            return FQNovelResponse.error("获取章节内容失败: 签名生成失败（已重试仍失败）");
        }
        log.error("获取章节内容失败 - itemIds: {}", itemIds, e);
        return FQNovelResponse.error("获取章节内容失败: " + message);
    }

    private static boolean isIllegalAccess(long code, String message, String rawBody) {
        if (code == ILLEGAL_ACCESS_CODE) {
            return true;
        }
        String msg = message != null ? message : "";
        String raw = rawBody != null ? rawBody : "";
        return msg.contains("ILLEGAL_ACCESS") || raw.contains("ILLEGAL_ACCESS");
    }

    /**
     * 获取书籍信息 (从目录接口获取完整信息)
     *
     * @param bookId 书籍ID
     * @return 书籍信息
     */
    public CompletableFuture<FQNovelResponse<FQNovelBookInfo>> getBookInfo(String bookId) {
        final String trimmedBookId = bookId != null ? bookId.trim() : "";
        if (trimmedBookId.isEmpty()) {
            return CompletableFuture.completedFuture(FQNovelResponse.error("书籍ID不能为空"));
        }

        FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
        directoryRequest.setBookId(trimmedBookId);
        directoryRequest.setBookType(0);
        directoryRequest.setNeedVersion(true);
        directoryRequest.setMinimalResponse(false);

        // 避免在同一线程池内阻塞 .get() 导致线程耗尽/死锁：使用 thenApply 链式处理
        return fqSearchService.getBookDirectory(directoryRequest)
            .thenApply(directoryResponse -> {
                if (directoryResponse == null) {
                    return FQNovelResponse.<FQNovelBookInfo>error("获取书籍目录失败: 空响应");
                }
                if (directoryResponse.getCode() == null || directoryResponse.getCode() != 0 || directoryResponse.getData() == null) {
                    String msg = directoryResponse.getMessage();
                    if (msg == null || msg.trim().isEmpty() || "success".equalsIgnoreCase(msg.trim())) {
                        msg = "目录接口未返回有效数据";
                    }
                    return FQNovelResponse.<FQNovelBookInfo>error("获取书籍目录失败: " + msg);
                }

                FQDirectoryResponse directoryData = directoryResponse.getData();
                FQNovelBookInfoResp bookInfoResp = directoryData.getBookInfo();
                if (bookInfoResp == null) {
                    return FQNovelResponse.<FQNovelBookInfo>error("书籍信息不存在");
                }

                try {
                    FQNovelBookInfo bookInfo = mapBookInfoRespToBookInfo(bookInfoResp, trimmedBookId);

                    log.debug("调试信息 - bookId: {}, directoryData.serialCount: {}, bookInfoResp.serialCount: {}, directoryData.catalogData.size: {}",
                        trimmedBookId, directoryData.getSerialCount(), bookInfoResp.getSerialCount(),
                        directoryData.getCatalogData() != null ? directoryData.getCatalogData().size() : "null");

                    if (bookInfoResp.getSerialCount() != null) {
                        bookInfo.setTotalChapters(bookInfoResp.getSerialCount());
                        log.debug("使用bookInfo.serialCount获取章节总数 - bookId: {}, 章节数: {}", trimmedBookId, bookInfoResp.getSerialCount());
                    } else if (directoryData.getSerialCount() != null) {
                        bookInfo.setTotalChapters(directoryData.getSerialCount());
                        log.info("使用目录接口serial_count获取章节总数 - bookId: {}, 章节数: {}", trimmedBookId, directoryData.getSerialCount());
                    } else {
                        List<FQDirectoryResponse.CatalogItem> catalogData = directoryData.getCatalogData();
                        if (catalogData != null && !catalogData.isEmpty()) {
                            bookInfo.setTotalChapters(catalogData.size());
                            log.info("从目录数据获取章节总数 - bookId: {}, 章节数: {}", trimmedBookId, catalogData.size());
                        } else {
                            bookInfo.setTotalChapters(0);
                            log.warn("无法获取章节总数 - bookId: {}", trimmedBookId);
                        }
                    }

                    return FQNovelResponse.success(bookInfo);
                } catch (Exception e) {
                    log.error("获取书籍信息失败 - bookId: {}", trimmedBookId, e);
                    return FQNovelResponse.<FQNovelBookInfo>error("获取书籍信息失败: " + e.getMessage());
                }
            })
            .exceptionally(e -> {
                Throwable t = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                log.error("获取书籍信息失败 - bookId: {}", trimmedBookId, t);
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                return FQNovelResponse.error("获取书籍信息失败: " + msg);
            });
    }

    /**
     * 将FQNovelBookInfoResp转换为FQNovelBookInfo（精简版 - 仅映射必要字段）
     *
     * @param resp 原始响应对象
     * @param bookId 书籍ID
     * @return 映射后的书籍信息对象
     */
    private FQNovelBookInfo mapBookInfoRespToBookInfo(FQNovelBookInfoResp resp, String bookId) {
        FQNovelBookInfo info = new FQNovelBookInfo();
        
        // 只映射 Simple 版本需要的字段
        info.setBookId(bookId);
        info.setBookName(resp.getBookName());
        info.setAuthor(resp.getAuthor());
        String description = resp.getAbstractContent();
        if (description == null || description.trim().isEmpty()) {
            description = resp.getBookAbstractV2();
        }
        info.setDescription(description);
        info.setCoverUrl(resp.getThumbUrl());
        info.setWordNumber(resp.getWordNumber());
        info.setLastChapterTitle(resp.getLastChapterTitle());
        info.setCategory(resp.getCategory());
        info.setStatus(resp.getStatus() != null ? resp.getStatus() : 0);
        
        return info;
    }
}
