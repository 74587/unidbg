package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQApiProperties;
import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.utils.FQApiUtils;
import com.anjia.unidbgserver.utils.GzipUtils;
import com.anjia.unidbgserver.utils.ProcessLifecycle;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private FQRegisterKeyService registerKeyService;

    @Resource
    private FQContentService fqContentService;

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
                    Map<String, String> signedHeaders = fqEncryptServiceWorker.generateSignatureHeaders(fullUrl, headers).get();
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 验证bookId参数
                if (bookId == null || bookId.trim().isEmpty()) {
                    return FQNovelResponse.error("书籍ID不能为空");
                }

                // 构建目录请求
                FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
                directoryRequest.setBookId(bookId);
                directoryRequest.setBookType(0);
                directoryRequest.setNeedVersion(true);

                // 调用目录接口获取书籍信息
                FQNovelResponse<FQDirectoryResponse> directoryResponse = fqSearchService.getBookDirectory(directoryRequest).get();

                if (directoryResponse.getCode() != 0 || directoryResponse.getData() == null) {
                    String msg = directoryResponse.getMessage();
                    if (msg == null || msg.trim().isEmpty() || "success".equalsIgnoreCase(msg.trim())) {
                        msg = "目录接口未返回有效数据";
                    }
                    return FQNovelResponse.error("获取书籍目录失败: " + msg);
                }

                FQDirectoryResponse directoryData = directoryResponse.getData();
                FQNovelBookInfoResp bookInfoResp = directoryData.getBookInfo();

                if (bookInfoResp == null) {
                    return FQNovelResponse.error("书籍信息不存在");
                }

                // 从FQNovelBookInfoResp转换为FQNovelBookInfo（完整映射）
                FQNovelBookInfo bookInfo = mapBookInfoRespToBookInfo(bookInfoResp, bookId);

                // 章节总数 - 优先使用目录接口的serial_count字段获取真实章节数
                log.debug("调试信息 - bookId: {}, directoryData.serialCount: {}, bookInfoResp.serialCount: {}, directoryData.catalogData.size: {}", 
                    bookId, directoryData.getSerialCount(), bookInfoResp.getSerialCount(),
                    directoryData.getCatalogData() != null ? directoryData.getCatalogData().size() : "null");
                
                // 优先从bookInfo中获取serialCount
                if (bookInfoResp.getSerialCount() != null) {
                    bookInfo.setTotalChapters(bookInfoResp.getSerialCount());
                    log.debug("使用bookInfo.serialCount获取章节总数 - bookId: {}, 章节数: {}", bookId, bookInfoResp.getSerialCount());
                } else if (directoryData.getSerialCount() != null) {
                    bookInfo.setTotalChapters(directoryData.getSerialCount());
                    log.info("使用目录接口serial_count获取章节总数 - bookId: {}, 章节数: {}", bookId, directoryData.getSerialCount());
                } else {
                    // 如果两个serial_count都为空，尝试从目录数据获取
                    List<FQDirectoryResponse.CatalogItem> catalogData = directoryData.getCatalogData();
                    if (catalogData != null && !catalogData.isEmpty()) {
                        bookInfo.setTotalChapters(catalogData.size());
                        log.info("从目录数据获取章节总数 - bookId: {}, 章节数: {}", bookId, catalogData.size());
                    } else {
                        bookInfo.setTotalChapters(0);
                        log.warn("无法获取章节总数 - bookId: {}", bookId);
                    }
                }

                return FQNovelResponse.success(bookInfo);

            } catch (Exception e) {
                log.error("获取书籍信息失败 - bookId: {}", bookId, e);
                return FQNovelResponse.error("获取书籍信息失败: " + e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 获取解密的章节内容
     *
     * @param itemIds 章节ID列表，逗号分隔
     * @param bookId 书籍ID
     * @param download 是否下载模式
     * @return 解密后的章节内容列表
     */
    public CompletableFuture<FQNovelResponse<List<Map.Entry<String, String>>>> getDecryptedContents(String itemIds, String bookId, boolean download) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 先获取内容
                FQNovelResponse<FqIBatchFullResponse> batchResponse = batchFull(itemIds, bookId, download).get();

                if (batchResponse.getCode() != 0 || batchResponse.getData() == null) {
                    return FQNovelResponse.error("获取内容失败: " + batchResponse.getMessage());
                }

                // 解密内容
                List<Map.Entry<String, String>> decryptedContents =
                    fqContentService.decryptBatchContents(batchResponse.getData());

                return FQNovelResponse.success(decryptedContents);

            } catch (Exception e) {
                log.error("获取解密章节内容失败 - itemIds: {}", itemIds, e);
                return FQNovelResponse.error("获取解密章节内容失败: " + e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 获取章节内容 (使用新的API模式)
     *
     * @param request 包含书籍ID和章节ID的请求
     * @return 章节内容
     */
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(FQNovelRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (request.getBookId() == null || request.getChapterId() == null) {
                    return FQNovelResponse.error("书籍ID和章节ID不能为空");
                }

                // 使用batch_full API获取完整响应数据
                String itemIds = request.getChapterId();
                FQNovelResponse<FqIBatchFullResponse> batchResponse = batchFull(itemIds, request.getBookId(), false, request.getToken()).get();

                if (batchResponse.getCode() != 0 || batchResponse.getData() == null) {
                    return FQNovelResponse.error("获取章节内容失败: " + batchResponse.getMessage());
                }

                FqIBatchFullResponse batchFullResponse = batchResponse.getData();
                Map<String, ItemContent> dataMap = batchFullResponse.getData();

                if (dataMap == null || dataMap.isEmpty()) {
                    return FQNovelResponse.error("未找到章节数据");
                }

                // 获取第一个章节的内容
                String chapterId = request.getChapterId();
                ItemContent itemContent = dataMap.get(chapterId);

                if (itemContent == null) {
                    // 如果使用chapterId没找到，尝试使用第一个可用的key
                    itemContent = dataMap.values().iterator().next();
                    chapterId = dataMap.keySet().iterator().next();
                }

                if (itemContent == null) {
                    return FQNovelResponse.error("未找到章节内容");
                }

                // 解密章节内容
                String decryptedContent = "";
                try {
                    decryptedContent = fqContentService.decryptAndDecompress(itemContent);
                } catch (Exception e) {
                    log.error("解密章节内容失败 - chapterId: {}", chapterId, e);
                    return FQNovelResponse.error("解密章节内容失败: " + e.getMessage());
                }

                // 从HTML中提取纯文本内容
                String txtContent = extractTextFromHtml(decryptedContent);

                // 构建章节信息对象
                FQNovelChapterInfo chapterInfo = new FQNovelChapterInfo();
                chapterInfo.setChapterId(chapterId);
                chapterInfo.setBookId(request.getBookId());
                chapterInfo.setRawContent(decryptedContent);
                chapterInfo.setTxtContent(txtContent);

                // 从ItemContent中提取标题
                String title = itemContent.getTitle();
                if (title == null || title.trim().isEmpty()) {
                    // 如果title为空，尝试从HTML中提取标题
                    Pattern titlePattern = Pattern.compile("<h1[^>]*>.*?<blk[^>]*>([^<]*)</blk>.*?</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                    Matcher titleMatcher = titlePattern.matcher(decryptedContent);
                    if (titleMatcher.find()) {
                        title = titleMatcher.group(1).trim();
                    } else {
                        title = "章节标题";
                    }
                }
                chapterInfo.setTitle(title);

                // 从novelData中提取作者信息（如果可用）
                FQNovelData novelData = itemContent.getNovelData();
                chapterInfo.setAuthorName(novelData != null ? novelData.getAuthor() : "未知作者");
                // 设置其他字段
                chapterInfo.setWordCount(txtContent.length());
                chapterInfo.setUpdateTime(System.currentTimeMillis());

                return FQNovelResponse.success(chapterInfo);

            } catch (Exception e) {
                log.error("获取章节内容失败 - bookId: {}, chapterId: {}",
                    request.getBookId(), request.getChapterId(), e);
                return FQNovelResponse.error("获取章节内容失败: " + e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 从HTML内容中提取纯文本
     * 主要提取 <blk> 标签中的文本内容，按照 e_order 排序
     *
     * @param htmlContent HTML内容
     * @return 提取的纯文本内容
     */
    private String extractTextFromHtml(String htmlContent) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();

        try {
            // 使用正则表达式提取 <blk> 标签中的文本内容
            Pattern blkPattern = Pattern.compile("<blk[^>]*>([^<]*)</blk>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = blkPattern.matcher(htmlContent);

            while (matcher.find()) {
                String text = matcher.group(1);
                if (text != null && !text.trim().isEmpty()) {
                    textBuilder.append(text.trim()).append("\n");
                }
            }

            // 如果没有找到 <blk> 标签，尝试提取所有文本内容
            if (textBuilder.length() == 0) {
                // 简单的HTML标签移除，保留文本内容
                String text = htmlContent.replaceAll("<[^>]+>", "").trim();
                if (!text.isEmpty()) {
                    textBuilder.append(text);
                }
            }

        } catch (Exception e) {
            log.warn("HTML文本提取失败，返回原始内容", e);
            // 如果解析失败，返回去除HTML标签的简单文本
            return htmlContent.replaceAll("<[^>]+>", "").trim();
        }

        return textBuilder.toString().trim();
    }

    /**
     * 将FQNovelBookInfoResp转换为FQNovelBookInfo（完整字段映射）
     *
     * @param resp 原始响应对象
     * @param bookId 书籍ID
     * @return 映射后的书籍信息对象
     */
    private FQNovelBookInfo mapBookInfoRespToBookInfo(FQNovelBookInfoResp resp, String bookId) {
        FQNovelBookInfo info = new FQNovelBookInfo();
        
        // ============ 基础信息 ============
        info.setBookId(bookId);
        info.setBookName(resp.getBookName());
        info.setBookShortName(resp.getBookShortName());
        info.setAuthor(resp.getAuthor());
        info.setAuthorId(resp.getAuthorId());
        
        // 作者信息 - 转换为Map
        if (resp.getAuthorInfo() != null) {
            try {
                String authorInfoJson = objectMapper.writeValueAsString(resp.getAuthorInfo());
                Map<String, Object> authorInfoMap = objectMapper.readValue(authorInfoJson, new TypeReference<Map<String, Object>>() {});
                info.setAuthorInfo(authorInfoMap);
            } catch (Exception e) {
                log.warn("转换作者信息失败", e);
            }
        }
        
        info.setDescription(resp.getAbstractContent());
        info.setBookAbstractV2(resp.getBookAbstractV2());
        info.setCoverUrl(resp.getThumbUrl());
        info.setDetailPageThumbUrl(resp.getDetailPageThumbUrl());
        info.setExpandThumbUrl(resp.getExpandThumbUrl());
        info.setHorizThumbUrl(resp.getHorizThumbUrl());
        
        // 状态
        info.setStatus(resp.getStatus() != null ? resp.getStatus() : 0);
        
        info.setCreationStatus(resp.getCreationStatus());
        info.setUpdateStatus(resp.getUpdateStatus());
        info.setUpdateStop(resp.getUpdateStop());
        
        // ============ 章节信息 ============
        info.setWordNumber(resp.getWordNumber());
        info.setFirstChapterTitle(resp.getFirstChapterTitle());
        info.setFirstChapterItemId(resp.getFirstChapterItemId());
        info.setFirstChapterGroupId(resp.getFirstChapterGroupId());
        info.setLastChapterTitle(resp.getLastChapterTitle());
        info.setLastChapterItemId(resp.getLastChapterItemId());
        info.setLastChapterGroupId(resp.getLastChapterGroupId());
        info.setLastChapterUpdateTime(resp.getLastChapterUpdateTime());
        info.setLastChapterFirstPassTime(resp.getLastChapterFirstPassTime());
        info.setRealChapterOrder(resp.getRealChapterOrder());
        
        // ============ 分类信息 ============
        info.setCategory(resp.getCategory());
        info.setCategoryV2(resp.getCategoryV2());
        info.setCategoryV2Ids(resp.getCategoryV2Ids());
        info.setCategorySchema(resp.getCategorySchema());
        info.setCompleteCategory(resp.getCompleteCategory());
        info.setPureCategoryTags(resp.getPureCategoryTags());
        info.setGenre(resp.getGenre());
        info.setGenreType(resp.getGenreType());
        info.setSubGenre(resp.getSubGenre());
        info.setTags(resp.getTags());
        info.setGender(resp.getGender());
        
        // ============ 统计数据 ============
        info.setReadCount(resp.getReadCount());
        info.setReadCountAll(resp.getReadCountAll());
        info.setReadCntText(resp.getReadCntText());
        info.setReadDcnt30d(resp.getReadDcnt30d());
        info.setAddBookshelfCount(resp.getAddBookshelfCount());
        info.setAllBookshelfCount(resp.getAllBookshelfCount());
        info.setAddShelfCount14d(resp.getAddShelfCount14d());
        info.setShelfCntHistory(resp.getShelfCntHistory());
        info.setReaderUv14day(resp.getReaderUv14day());
        info.setReaderUvSumDaily(resp.getReaderUvSumDaily());
        info.setListenCount(resp.getListenCount());
        info.setListenUv14day(resp.getListenUv14day());
        info.setListenUv30day(resp.getListenUv30day());
        info.setScore(resp.getScore());
        info.setFinishRate10(resp.getFinishRate10());
        info.setDataRate(resp.getDataRate());
        info.setRiskRate(resp.getRiskRate());
        info.setRecommendCountLevel(resp.getRecommendCountLevel());
        
        // ============ 价格与销售 ============
        info.setTotalPrice(resp.getTotalPrice());
        info.setCustomTotalPrice(resp.getCustomTotalPrice());
        info.setDiscountPrice(resp.getDiscountPrice());
        info.setDiscountCustomTotalPrice(resp.getDiscountCustomTotalPrice());
        info.setBasePrice(resp.getBasePrice());
        info.setSaleStatus(resp.getSaleStatus());
        info.setSaleType(resp.getSaleType());
        info.setFreeStatus(resp.getFreeStatus());
        info.setVipBook(resp.getVipBook());
        
        // ============ 授权与版权 ============
        info.setExclusive(resp.getExclusive());
        info.setRealExclusive(resp.getRealExclusive());
        info.setAuthorizeType(resp.getAuthorizeType());
        info.setCopyrightInfo(resp.getCopyrightInfo());
        info.setContractAuthorize(resp.getContractAuthorize());
        
        // ============ 音频相关 ============
        info.setAudioThumbUri(resp.getAudioThumbUri());
        info.setAudioThumbUrlHd(resp.getAudioThumbUrlHd());
        info.setColorAudioDominate(resp.getColorAudioDominate());
        info.setColorAudioMostPopular(resp.getColorAudioMostPopular());
        info.setAudioEnableRandomPlay(resp.getAudioEnableRandomPlay());
        info.setHideListenBall(resp.getHideListenBall());
        info.setDuration(resp.getDuration());
        info.setRelatedAudioBookId(resp.getRelatedAudioBookId());
        info.setRelatedAudioBookids(resp.getRelatedAudioBookids());
        info.setHasMatchAudioBooks(resp.getHasMatchAudioBooks());
        
        // ============ 显示与颜色 ============
        info.setColorDominate(resp.getColorDominate());
        info.setColorMostPopular(resp.getColorMostPopular());
        info.setThumbUri(resp.getThumbUri());
        info.setUseSquarePic(resp.getUseSquarePic());
        info.setThumbConfirmStatus(resp.getThumbConfirmStatus());
        info.setOpThumbUri(resp.getOpThumbUri());
        
        // ============ 时间信息 ============
        info.setCreateTime(resp.getCreateTime());
        info.setPublishedDate(resp.getPublishedDate());
        info.setLastPublishTime(resp.getLastPublishTime());
        info.setFirstOnlineTime(resp.getFirstOnlineTime());
        info.setFirstVisibleTime(resp.getFirstVisibleTime());
        info.setLatestReadTime(resp.getLatestReadTime());
        info.setLatestListenTime(resp.getLatestListenTime());
        
        // ============ 书籍类型 ============
        info.setBookType(resp.getBookType());
        info.setIsNew(resp.getIsNew());
        info.setIsEbook(resp.getIsEbook());
        info.setIsLaobai(resp.getIsLaobai());
        info.setLengthType(resp.getLengthType());
        info.setNovelTextType(resp.getNovelTextType());
        info.setNovelBookThumbType(resp.getNovelBookThumbType());
        
        // ============ 其他信息 ============
        info.setBookSearchVisible(resp.getBookSearchVisible());
        info.setVisibilityInfo(resp.getVisibilityInfo());
        info.setRegionVisibilityInfo(resp.getRegionVisibilityInfo());
        info.setPress(resp.getPress());
        info.setPublisher(resp.getPublisher());
        info.setIsbn(resp.getIsbn());
        info.setSource(resp.getSource());
        info.setPlatform(resp.getPlatform());
        info.setPlatformBookId(resp.getPlatformBookId());
        info.setFlightFlag(resp.getFlightFlag());
        info.setBookFlightVersionId(resp.getBookFlightVersionId());
        info.setBookFlightAliasName(resp.getBookFlightAliasName());
        info.setBookFlightAliasThumb(resp.getBookFlightAliasThumb());
        info.setBindReputationBookId(resp.getBindReputationBookId());
        info.setModifiedReputationBookName(resp.getModifiedReputationBookName());
        info.setReputationThumbUri(resp.getReputationThumbUri());
        info.setReputationAuditStatus(resp.getReputationAuditStatus());
        info.setReputationLatestSetTime(resp.getReputationLatestSetTime());
        info.setExtraWordNumber(resp.getExtraWordNumber());
        info.setHasExtraChapter(resp.getHasExtraChapter());
        info.setAuthorModifyChapterSwitch(resp.getAuthorModifyChapterSwitch());
        info.setBindAuthorIds(resp.getBindAuthorIds());
        info.setKeepPublishDays(resp.getKeepPublishDays());
        info.setKeepUpdateDays(resp.getKeepUpdateDays());
        info.setWillKeepUpdateDays(resp.getWillKeepUpdateDays());
        info.setEstimatedChapterCount(resp.getEstimatedChapterCount());
        info.setContentChapterNumber(resp.getContentChapterNumber());
        info.setDisableReaderFeature(resp.getDisableReaderFeature());
        info.setTtsStatus(resp.getTtsStatus());
        info.setTtsDistribution(resp.getTtsDistribution());
        info.setTtsRecBlock(resp.getTtsRecBlock());
        info.setChangduProfileScore(resp.getChangduProfileScore());
        info.setWriteExtraPermission(resp.getWriteExtraPermission());
        info.setCreationLatestFinishTime(resp.getCreationLatestFinishTime());
        
        return info;
    }
}
