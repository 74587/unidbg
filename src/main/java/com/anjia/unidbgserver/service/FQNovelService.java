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
        return batchFull(itemIds, bookId, download, null);
    }

    /**
     * 获取章节内容 (基于 fqnovel-api 的 batch_full 方法)
     *
     * @param itemIds 章节ID列表，逗号分隔
     * @param bookId 书籍ID
     * @param download 是否下载模式 (false=在线阅读, true=下载)
     * @param token 用户 token（可选，用于付费章节；会参与签名）
     * @return 内容响应
     */
    public CompletableFuture<FQNovelResponse<FqIBatchFullResponse>> batchFull(String itemIds, String bookId, boolean download, String token) {
        return CompletableFuture.supplyAsync(() -> {
            if (ProcessLifecycle.isShuttingDown()) {
                return FQNovelResponse.error("服务正在退出中，请稍后重试");
            }

            int maxAttempts = Math.max(1, downloadProperties.getMaxRetries());
            long baseDelayMs = Math.max(0L, downloadProperties.getRetryDelayMs());
            long maxDelayMs = Math.max(baseDelayMs, downloadProperties.getRetryMaxDelayMs());

            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    FqVariable var = getDefaultFqVariable();

                    // 使用工具类构建URL和参数
                    String url = fqApiUtils.getBaseUrl() + "/reading/reader/batch_full/v";
                    Map<String, String> params = fqApiUtils.buildBatchFullParams(var, itemIds, bookId, download);
                    String fullUrl = fqApiUtils.buildUrlWithParams(url, params);

                    // 使用工具类构建请求头
                    Map<String, String> headers = fqApiUtils.buildCommonHeaders();
                    headers = withOptionalToken(headers, token);

                    // 使用现有的签名服务生成签名
                    upstreamRateLimiter.acquire();
                    Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeadersSync(fullUrl, headers);
                    if (signedHeaders == null || signedHeaders.isEmpty()) {
                        throw new IllegalStateException("签名生成失败");
                    }

                    // 发起API请求
                    HttpHeaders httpHeaders = new HttpHeaders();
                    headers.forEach(httpHeaders::set);
                    signedHeaders.forEach(httpHeaders::set);

                    HttpEntity<String> entity = new HttpEntity<>(httpHeaders);
                    ResponseEntity<byte[]> response = restTemplate.exchange(fullUrl, HttpMethod.GET, entity, byte[].class);

                    String responseBody = GzipUtils.decodeUpstreamResponse(response);

                    // 如果响应体为空，视为需要更换设备并重试
                    String trimmedBody = responseBody.trim();
                    if (trimmedBody.isEmpty()) {
                        throw new RuntimeException("Empty upstream response");
                    }

                    // 上游可能返回 HTML/非 JSON（例如风控/拦截页）；这种情况也走自愈重试
                    if (trimmedBody.startsWith("<")) {
                        if (trimmedBody.contains("ILLEGAL_ACCESS")) {
                            throw new IllegalStateException("ILLEGAL_ACCESS");
                        }
                        throw new IllegalStateException("UPSTREAM_NON_JSON");
                    }
                    if (!trimmedBody.startsWith("{") && !trimmedBody.startsWith("[")) {
                        if (trimmedBody.contains("ILLEGAL_ACCESS")) {
                            throw new IllegalStateException("ILLEGAL_ACCESS");
                        }
                        throw new IllegalStateException("UPSTREAM_NON_JSON");
                    }

                    // 解析响应
                    FqIBatchFullResponse batchResponse = objectMapper.readValue(responseBody, FqIBatchFullResponse.class);

                    if (batchResponse == null) {
                        throw new RuntimeException("Upstream parse failed");
                    }

                    if (batchResponse.getCode() != 0) {
                        String msg = batchResponse.getMessage() != null ? batchResponse.getMessage() : "";
                        String raw = responseBody;
                        if (isIllegalAccess(batchResponse.getCode(), msg, raw)) {
                            throw new IllegalStateException("ILLEGAL_ACCESS");
                        }
                        return FQNovelResponse.error((int) batchResponse.getCode(), msg);
                    }

                    autoRestartService.recordSuccess();
                    return FQNovelResponse.success(batchResponse);

                } catch (Exception e) {
                    String message = e.getMessage() != null ? e.getMessage() : "";
                    boolean illegal = message.contains("ILLEGAL_ACCESS");
                    boolean empty = message.contains("Empty upstream response") || message.contains("No content to map due to end-of-input");
                    boolean gzipErr = message.contains("Not in GZIP format");
                    boolean nonJson = message.contains("UPSTREAM_NON_JSON");
                    boolean signerFail = message.contains("签名生成失败");

                    boolean retryable = illegal || empty || gzipErr || nonJson || signerFail;
                    if (!retryable || attempt >= maxAttempts) {
                        if (retryable) {
                            String reason = illegal ? "ILLEGAL_ACCESS"
                                : (empty ? "UPSTREAM_EMPTY"
                                : (gzipErr ? "UPSTREAM_GZIP"
                                : (nonJson ? "UPSTREAM_NON_JSON" : "SIGNER_FAIL")));
                            autoRestartService.recordFailure(reason);
                        }
                        if (retryable && illegal) {
                            return FQNovelResponse.error("获取章节内容失败: ILLEGAL_ACCESS（已重试仍失败，建议更换设备/降低频率）");
                        }
                        if (retryable && gzipErr) {
                            return FQNovelResponse.error("获取章节内容失败: 响应格式异常（已重试仍失败）");
                        }
                        if (retryable && nonJson) {
                            return FQNovelResponse.error("获取章节内容失败: 上游返回非JSON（已重试仍失败）");
                        }
                        if (retryable && empty) {
                            return FQNovelResponse.error("获取章节内容失败: 空响应（已重试仍失败）");
                        }
                        if (retryable && signerFail) {
                            return FQNovelResponse.error("获取章节内容失败: 签名生成失败（已重试仍失败）");
                        }
                        log.error("获取章节内容失败 - itemIds: {}", itemIds, e);
                        return FQNovelResponse.error("获取章节内容失败: " + message);
                    }

                    if (retryable) {
                        String rotateReason = illegal ? "ILLEGAL_ACCESS"
                            : (empty ? "UPSTREAM_EMPTY"
                            : (gzipErr ? "UPSTREAM_GZIP"
                            : (nonJson ? "UPSTREAM_NON_JSON" : "SIGNER_FAIL")));

                        if (signerFail || (empty && attempt >= 2)) {
                            FQEncryptServiceWorker.requestGlobalReset(rotateReason);
                        }
                        if (illegal) {
                            deviceRotationService.forceRotate(rotateReason);
                        } else {
                            deviceRotationService.rotateIfNeeded(rotateReason);
                        }
                    }

                    // 指数退避 + 轻微抖动，避免并发重试打爆上游
                    long delay = baseDelayMs <= 0 ? 0 : baseDelayMs * (1L << Math.min(10, attempt - 1));
                    delay = Math.min(delay, maxDelayMs);
                    delay += ThreadLocalRandom.current().nextLong(0, 250);
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return FQNovelResponse.error("获取章节内容失败: 重试被中断");
                    }
                }
            }
            return FQNovelResponse.error("获取章节内容失败: 超过最大重试次数");
        }, taskExecutor);
    }

    private static Map<String, String> withOptionalToken(Map<String, String> headers, String token) {
        if (headers == null) {
            return headers;
        }
        if (token == null || token.trim().isEmpty()) {
            return headers;
        }
        String trimmed = token.trim();

        // 尽量保持 header 顺序稳定：将 token 放在 cookie 后面
        Map<String, String> ordered = new java.util.LinkedHashMap<>();
        boolean inserted = false;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            ordered.put(entry.getKey(), entry.getValue());
            if (!inserted && "cookie".equalsIgnoreCase(entry.getKey())) {
                ordered.put("x-tt-token", trimmed);
                inserted = true;
            }
        }
        if (!inserted) {
            ordered.put("x-tt-token", trimmed);
        }
        return ordered;
    }

    private static boolean isIllegalAccess(long code, String message, String rawBody) {
        if (code == 110) {
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
