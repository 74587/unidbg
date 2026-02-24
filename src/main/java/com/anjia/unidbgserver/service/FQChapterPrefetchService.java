package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.dto.FQDirectoryRequest;
import com.anjia.unidbgserver.dto.FQDirectoryResponse;
import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import com.anjia.unidbgserver.dto.FQNovelData;
import com.anjia.unidbgserver.dto.FQNovelRequest;
import com.anjia.unidbgserver.dto.FQNovelResponse;
import com.anjia.unidbgserver.dto.ItemContent;
import com.anjia.unidbgserver.utils.HtmlTextExtractor;
import com.anjia.unidbgserver.utils.LocalCacheFactory;
import com.anjia.unidbgserver.utils.Texts;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.BadPaddingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

/**
 * 单章接口的抗风控优化：
 * - 根据目录预取一段章节（调用上游 batch_full）
 * - 将结果缓存，后续单章请求直接命中缓存，显著减少上游调用次数
 */
@Service
public class FQChapterPrefetchService {

    private static final Logger log = LoggerFactory.getLogger(FQChapterPrefetchService.class);

    private static final int MIN_DIRECTORY_CACHE_MAX_ENTRIES = 64;
    private static final int MAX_CHAPTER_PREFETCH_SIZE = 30;

    // 修复问题 #7：定义魔法数字为常量
    /**
     * Base64 编码的最小有效长度（16 bytes IV + 至少1 byte数据 = 24 chars）
     */
    private static final int MIN_BASE64_ENCRYPTED_LENGTH = 24;
    private static final String EX_PREFIX_ILLEGAL_ARGUMENT = "java.lang.IllegalArgumentException:";
    private static final String EX_PREFIX_ILLEGAL_STATE = "java.lang.IllegalStateException:";
    private static final String EX_PREFIX_RUNTIME = "java.lang.RuntimeException:";
    private static final String DEFAULT_CHAPTER_TITLE = "章节标题";
    private static final String DEFAULT_AUTHOR_NAME = "未知作者";
    private static final String[] FAILURE_REASON_PREFIXES = {
        EX_PREFIX_ILLEGAL_ARGUMENT,
        EX_PREFIX_ILLEGAL_STATE,
        EX_PREFIX_RUNTIME
    };
    
    private final FQDownloadProperties downloadProperties;
    private final FQNovelService fqNovelService;
    private final FQSearchService fqSearchService;
    private final FQRegisterKeyService registerKeyService;
    private final ObjectProvider<PgChapterCacheService> pgChapterCacheServiceProvider;
    @Qualifier("fqPrefetchExecutor")
    private final Executor prefetchExecutor;

    private Cache<String, FQNovelChapterInfo> chapterCache;
    private Cache<String, String> chapterNegativeCache;
    private Cache<String, DirectoryIndex> directoryCache;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inflightPrefetch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<DirectoryIndex>> inflightDirectory = new ConcurrentHashMap<>();

    public FQChapterPrefetchService(
        FQDownloadProperties downloadProperties,
        FQNovelService fqNovelService,
        FQSearchService fqSearchService,
        FQRegisterKeyService registerKeyService,
        ObjectProvider<PgChapterCacheService> pgChapterCacheServiceProvider,
        @Qualifier("fqPrefetchExecutor") Executor prefetchExecutor
    ) {
        this.downloadProperties = downloadProperties;
        this.fqNovelService = fqNovelService;
        this.fqSearchService = fqSearchService;
        this.registerKeyService = registerKeyService;
        this.pgChapterCacheServiceProvider = pgChapterCacheServiceProvider;
        this.prefetchExecutor = prefetchExecutor;
    }

    @PostConstruct
    public void initCaches() {
        int chapterMax = Math.max(1, downloadProperties.getChapterCacheMaxEntries());
        long chapterTtl = downloadProperties.getChapterCacheTtlMs();
        long chapterNegativeTtl = Math.max(0L, downloadProperties.getChapterNegativeCacheTtlMs());
        int dirMax = Math.max(MIN_DIRECTORY_CACHE_MAX_ENTRIES, chapterMax / 10);
        long dirTtl = downloadProperties.getApiDirectoryCacheTtlMs();

        this.chapterCache = LocalCacheFactory.build(chapterMax, chapterTtl);
        this.chapterNegativeCache = chapterNegativeTtl > 0 ? LocalCacheFactory.build(chapterMax, chapterNegativeTtl) : null;
        this.directoryCache = LocalCacheFactory.build(dirMax, dirTtl);
    }

    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(FQNovelRequest request) {
        if (request == null) {
            return errorFuture("请求不能为空");
        }

        final String bookId = Texts.trimToNull(request.getBookId());
        if (bookId == null) {
            return errorFuture("书籍ID不能为空");
        }

        final String chapterId = Texts.trimToNull(request.getChapterId());
        if (chapterId == null) {
            return errorFuture("章节ID不能为空");
        }

        FQNovelChapterInfo cached = getCachedChapter(bookId, chapterId);
        if (cached != null) {
            // 命中缓存直接返回，不走上游请求，也不会触发上游限流。
            return CompletableFuture.completedFuture(FQNovelResponse.success(cached));
        }

        // 主缓存：PostgreSQL（命中后回填本地 Caffeine）
        FQNovelChapterInfo persisted = getPersistedChapter(bookId, chapterId);
        if (persisted != null) {
            // 命中 PostgreSQL 主缓存直接返回，不走上游请求，也不会触发上游限流。
            return CompletableFuture.completedFuture(FQNovelResponse.success(persisted));
        }

        String cachedFailure = getCachedChapterFailure(bookId, chapterId);
        if (cachedFailure != null) {
            return errorFuture("获取章节内容失败: " + cachedFailure);
        }

        // 预取：优先在目录中定位章节顺序，拉取后缓存（非阻塞链式调用，避免线程池互等死锁）
        return prefetchAndCacheDedup(bookId, chapterId)
            .exceptionally(ex -> null) // 预取失败不影响单章兜底
            .thenCompose(ignored -> {
                FQNovelChapterInfo afterPrefetch = getCachedChapter(bookId, chapterId);
                if (afterPrefetch != null) {
                    return CompletableFuture.completedFuture(FQNovelResponse.success(afterPrefetch));
                }

                // 兜底：仍未命中则只取单章
                return fqNovelService.batchFull(chapterId, bookId, true).thenApply(single -> {
                    if (single.getCode() != 0 || single.getData() == null) {
                        return FQNovelResponse.<FQNovelChapterInfo>error("获取章节内容失败: " + single.getMessage());
                    }

                    Map<String, ItemContent> dataMap = single.getData().getData();
                    if (dataMap == null || dataMap.isEmpty()) {
                        return FQNovelResponse.<FQNovelChapterInfo>error("未找到章节数据");
                    }

                    ItemContent itemContent = dataMap.getOrDefault(chapterId, dataMap.values().iterator().next());
                    try {
                        FQNovelChapterInfo info = buildChapterInfo(bookId, chapterId, itemContent);
                        cacheChapter(bookId, chapterId, info);
                        return FQNovelResponse.success(info);
                    } catch (Exception e) {
                        cacheChapterFailure(bookId, chapterId, e.getMessage());
                        throw new RuntimeException(e);
                    }
                });
            })
            .exceptionally(e -> {
                Throwable t = unwrapCompletionException(e);
                String msg = exceptionMessage(t);
                cacheChapterFailure(bookId, chapterId, msg);
                if (msg.contains("Encrypted data too short") || msg.contains("章节内容为空/过短") || msg.contains("upstream item code=")) {
                    log.warn("单章获取失败 - bookId: {}, chapterId: {}, reason={}", bookId, chapterId, msg);
                    log.debug("单章获取失败详情 - bookId: {}, chapterId: {}", bookId, chapterId, t);
                } else {
                    log.error("单章获取失败 - bookId: {}, chapterId: {}", bookId, chapterId, t);
                }
                return FQNovelResponse.<FQNovelChapterInfo>error("获取章节内容失败: " + msg);
            });
    }

    private static Throwable unwrapCompletionException(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null) {
            return throwable.getCause();
        }
        return throwable;
    }

    private static String exceptionMessage(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        return Objects.requireNonNullElse(throwable.getMessage(), throwable.toString());
    }

    private Executor resolvePrefetchExecutor() {
        return prefetchExecutor != null ? prefetchExecutor : ForkJoinPool.commonPool();
    }

    private CompletableFuture<Void> prefetchAndCacheDedup(String bookId, String chapterId) {
        final String key = computePrefetchKeyFast(bookId, chapterId);

        CompletableFuture<Void> existing = inflightPrefetch.get(key);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Void> created = new CompletableFuture<>();
        existing = inflightPrefetch.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }

        doPrefetchAndCacheAsync(bookId, chapterId).whenComplete((v, ex) -> {
            try {
                if (ex != null) {
                    log.debug("预取失败（忽略） - bookId: {}, chapterId: {}", bookId, chapterId, ex);
                    created.completeExceptionally(ex);
                    return;
                }
                created.complete(null);
            } finally {
                inflightPrefetch.remove(key, created);
            }
        });

        return created;
    }

    private String computePrefetchKeyFast(String bookId, String chapterId) {
        DirectoryIndex directoryIndex = directoryCache.getIfPresent(bookId);
        if (directoryIndex == null || directoryIndex.getItemIds().isEmpty()) {
            return singlePrefetchKey(bookId, chapterId);
        }
        int index = directoryIndex.indexOf(chapterId);
        if (index < 0) {
            return singlePrefetchKey(bookId, chapterId);
        }
        int size = prefetchBatchSize();
        int bucketStart = (index / size) * size;
        return bookId + ":bucket:" + bucketStart + ":" + size;
    }

    private static String singlePrefetchKey(String bookId, String chapterId) {
        return bookId + ":single:" + chapterId;
    }

    private static <T> CompletableFuture<FQNovelResponse<T>> errorFuture(String message) {
        return CompletableFuture.completedFuture(FQNovelResponse.error(message));
    }

    private int prefetchBatchSize() {
        return Math.max(1, Math.min(MAX_CHAPTER_PREFETCH_SIZE, downloadProperties.getChapterPrefetchSize()));
    }

    private List<String> selectPrefetchBatchIds(List<String> itemIds, int chapterIndex, String chapterId) {
        if (chapterIndex < 0) {
            return Collections.singletonList(chapterId);
        }
        int endExclusive = Math.min(itemIds.size(), chapterIndex + prefetchBatchSize());
        return itemIds.subList(chapterIndex, endExclusive);
    }

    private CompletableFuture<Void> doPrefetchAndCacheAsync(String bookId, String chapterId) {
        Executor exec = resolvePrefetchExecutor();
        return getDirectoryIndexAsync(bookId).thenCompose(directoryIndex -> {
            List<String> itemIds = directoryIndex != null ? directoryIndex.getItemIds() : Collections.emptyList();
            if (itemIds.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            int index = directoryIndex.indexOf(chapterId);
            List<String> batchIds = selectPrefetchBatchIds(itemIds, index, chapterId);

            // 拉取并解密（处理放在 prefetchExecutor 上，避免占用业务线程池）
            String joined = String.join(",", batchIds);
            return fqNovelService.batchFull(joined, bookId, true).thenAcceptAsync(batch -> {
                if (batch == null || batch.getCode() != 0 || batch.getData() == null || batch.getData().getData() == null) {
                    return;
                }

                Map<String, ItemContent> dataMap = batch.getData().getData();
                for (String itemId : batchIds) {
                    ItemContent content = dataMap.get(itemId);
                    if (content == null) {
                        continue;
                    }
                    try {
                        FQNovelChapterInfo info = buildChapterInfo(bookId, itemId, content);
                        cacheChapter(bookId, itemId, info);
                    } catch (Exception e) {
                        cacheChapterFailure(bookId, itemId, e.getMessage());
                        log.debug("预取章节处理失败 - bookId: {}, itemId: {}", bookId, itemId, e);
                    }
                }
            }, exec);
        });
    }

    private CompletableFuture<DirectoryIndex> getDirectoryIndexAsync(String bookId) {
        DirectoryIndex cached = directoryCache.getIfPresent(bookId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<DirectoryIndex> inFlight = inflightDirectory.get(bookId);
        if (inFlight != null) {
            return inFlight;
        }

        CompletableFuture<DirectoryIndex> created = new CompletableFuture<>();
        inFlight = inflightDirectory.putIfAbsent(bookId, created);
        if (inFlight != null) {
            return inFlight;
        }

        try {
            FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
            directoryRequest.setBookId(bookId);
            directoryRequest.setMinimalResponse(true);

            fqSearchService.getBookDirectory(directoryRequest)
                .handle((resp, ex) -> {
                    if (ex != null || resp == null || resp.getCode() != 0 || resp.getData() == null || resp.getData().getItemDataList() == null) {
                        return DirectoryIndex.empty();
                    }

                    List<String> itemIds = new ArrayList<>();
                    Map<String, Integer> chapterIndex = new HashMap<>();
                    for (FQDirectoryResponse.ItemData item : resp.getData().getItemDataList()) {
                        if (item != null && Texts.hasText(item.getItemId())) {
                            String itemId = Texts.trimToEmpty(item.getItemId());
                            chapterIndex.put(itemId, itemIds.size());
                            itemIds.add(itemId);
                        }
                    }

                    DirectoryIndex directoryIndex = DirectoryIndex.of(itemIds, chapterIndex);
                    directoryCache.put(bookId, directoryIndex);
                    return directoryIndex;
                })
                .whenComplete((directoryIndex, ex) ->
                    completeDirectoryInflight(bookId, created, directoryIndex));
        } catch (Exception e) {
            completeDirectoryInflight(bookId, created, DirectoryIndex.empty());
        }

        return created;
    }

    private void completeDirectoryInflight(
        String bookId,
        CompletableFuture<DirectoryIndex> inflightFuture,
        DirectoryIndex directoryIndex
    ) {
        inflightFuture.complete(Objects.requireNonNullElse(directoryIndex, DirectoryIndex.empty()));
        inflightDirectory.remove(bookId, inflightFuture);
    }

    private static final class DirectoryIndex {
        private static final DirectoryIndex EMPTY = new DirectoryIndex(Collections.emptyList(), Collections.emptyMap());

        private final List<String> itemIds;
        private final Map<String, Integer> chapterIndex;

        private DirectoryIndex(List<String> itemIds, Map<String, Integer> chapterIndex) {
            this.itemIds = itemIds;
            this.chapterIndex = chapterIndex;
        }

        private static DirectoryIndex of(List<String> itemIds, Map<String, Integer> chapterIndex) {
            List<String> safeIds = itemIds == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(itemIds));
            Map<String, Integer> safeIndex = chapterIndex == null ? Collections.emptyMap() : Collections.unmodifiableMap(new HashMap<>(chapterIndex));
            return new DirectoryIndex(safeIds, safeIndex);
        }

        private static DirectoryIndex empty() {
            return EMPTY;
        }

        private List<String> getItemIds() {
            return itemIds;
        }

        private int indexOf(String chapterId) {
            if (chapterId == null) {
                return -1;
            }
            Integer index = chapterIndex.get(chapterId);
            return index != null ? index : -1;
        }
    }

    private FQNovelChapterInfo getCachedChapter(String bookId, String chapterId) {
        String key = cacheKey(bookId, chapterId);
        FQNovelChapterInfo cached = chapterCache.getIfPresent(key);
        if (!FQNovelChapterInfo.normalizeAndValidateForCache(bookId, chapterId, cached)) {
            if (cached != null) {
                chapterCache.invalidate(key);
            }
            return null;
        }
        return cached;
    }

    private FQNovelChapterInfo getPersistedChapter(String bookId, String chapterId) {
        PgChapterCacheService pgCacheService = pgChapterCacheServiceProvider.getIfAvailable();
        if (pgCacheService == null) {
            return null;
        }

        FQNovelChapterInfo persisted = pgCacheService.getChapter(bookId, chapterId);
        if (persisted != null) {
            chapterCache.put(cacheKey(bookId, chapterId), persisted);
            evictChapterFailure(bookId, chapterId);
        }
        return persisted;
    }

    private void cacheChapter(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (!FQNovelChapterInfo.normalizeAndValidateForCache(bookId, chapterId, chapterInfo)) {
            return;
        }

        chapterCache.put(cacheKey(bookId, chapterId), chapterInfo);
        evictChapterFailure(bookId, chapterId);

        PgChapterCacheService pgCacheService = pgChapterCacheServiceProvider.getIfAvailable();
        if (pgCacheService != null) {
            pgCacheService.saveChapterIfValid(bookId, chapterId, chapterInfo);
        }
    }

    private FQNovelChapterInfo buildChapterInfo(String bookId, String chapterId, ItemContent itemContent) throws Exception {
        if (itemContent == null) {
            throw new IllegalArgumentException("章节内容为空");
        }
        if (itemContent.getCode() != 0) {
            throw new IllegalStateException("upstream item code=" + itemContent.getCode());
        }
        String encrypted = Texts.trimToNull(itemContent.getContent());
        if (encrypted == null) {
            throw new IllegalArgumentException("章节内容为空/过短");
        }
        // Base64(16 bytes iv + ...) 的最短长度约为 24（含 padding）；过短通常是上游返回了异常内容
        if (encrypted.length() < MIN_BASE64_ENCRYPTED_LENGTH) {
            throw new IllegalArgumentException("章节内容为空/过短");
        }

        Long contentKeyver = itemContent.getKeyVersion();
        String decryptedContent = decryptChapterContentWithRetry(bookId, chapterId, encrypted, contentKeyver);
        return buildChapterInfoFromContent(
            bookId,
            chapterId,
            itemContent,
            decryptedContent,
            downloadProperties.isChapterIncludeRawContent()
        );
    }

    private static FQNovelChapterInfo buildChapterInfoFromContent(
        String bookId,
        String chapterId,
        ItemContent itemContent,
        String decryptedContent,
        boolean includeRawContent
    ) {
        String txtContent = HtmlTextExtractor.extractText(decryptedContent);

        FQNovelChapterInfo chapterInfo = new FQNovelChapterInfo();
        chapterInfo.setChapterId(chapterId);
        chapterInfo.setBookId(bookId);
        if (includeRawContent) {
            chapterInfo.setRawContent(decryptedContent);
        }
        chapterInfo.setTxtContent(txtContent);
        chapterInfo.setTitle(resolveChapterTitle(itemContent, decryptedContent));
        chapterInfo.setAuthorName(resolveAuthorName(itemContent));
        chapterInfo.setWordCount(txtContent.length());
        chapterInfo.setUpdateTime(System.currentTimeMillis());

        return chapterInfo;
    }

    private static String resolveChapterTitle(ItemContent itemContent, String decryptedContent) {
        String title = itemContent == null ? null : Texts.trimToNull(itemContent.getTitle());
        if (title != null) {
            return title;
        }
        String extractedTitle = HtmlTextExtractor.extractTitle(decryptedContent);
        return extractedTitle != null ? extractedTitle : DEFAULT_CHAPTER_TITLE;
    }

    private static String resolveAuthorName(ItemContent itemContent) {
        if (itemContent == null) {
            return DEFAULT_AUTHOR_NAME;
        }
        FQNovelData novelData = itemContent.getNovelData();
        if (novelData == null) {
            return DEFAULT_AUTHOR_NAME;
        }
        return Texts.defaultIfBlank(novelData.getAuthor(), DEFAULT_AUTHOR_NAME);
    }

    /**
     * BadPadding 通常意味着 key 不匹配。这里做一次受控重试：刷新 registerkey 后仅再尝试一次。
     */
    private String decryptChapterContentWithRetry(String bookId, String chapterId, String encrypted, Long contentKeyver) throws Exception {
        String key = registerKeyService.getDecryptionKey(contentKeyver);
        try {
            return FqCrypto.decryptAndDecompressContent(encrypted, key);
        } catch (BadPaddingException first) {
            log.warn("章节解密失败(BadPadding)，刷新registerkey后重试一次 - bookId: {}, chapterId: {}, keyver={}",
                bookId, chapterId, contentKeyver);
            registerKeyService.invalidateCurrentKey();
            registerKeyService.refreshRegisterKey();
            String retryKey = registerKeyService.getDecryptionKey(contentKeyver);
            return FqCrypto.decryptAndDecompressContent(encrypted, retryKey);
        }
    }

    private static String cacheKey(String bookId, String chapterId) {
        return bookId + ":" + chapterId;
    }

    private String getCachedChapterFailure(String bookId, String chapterId) {
        if (chapterNegativeCache == null) {
            return null;
        }
        String reason = chapterNegativeCache.getIfPresent(cacheKey(bookId, chapterId));
        if (!Texts.hasText(reason)) {
            return null;
        }
        return reason;
    }

    private void cacheChapterFailure(String bookId, String chapterId, String reason) {
        if (!isChapterNegativeCacheEligible(bookId, chapterId)) {
            return;
        }
        String normalized = normalizeFailureReason(reason);
        if (!isChapterFailureCacheable(normalized)) {
            return;
        }
        chapterNegativeCache.put(cacheKey(bookId, chapterId), normalized);
    }

    private void evictChapterFailure(String bookId, String chapterId) {
        if (!isChapterNegativeCacheEligible(bookId, chapterId)) {
            return;
        }
        chapterNegativeCache.invalidate(cacheKey(bookId, chapterId));
    }

    private boolean isChapterNegativeCacheEligible(String bookId, String chapterId) {
        return chapterNegativeCache != null && Texts.hasText(bookId) && Texts.hasText(chapterId);
    }

    private static String normalizeFailureReason(String reason) {
        if (!Texts.hasText(reason)) {
            return "";
        }
        String normalized = Texts.trimToEmpty(reason);
        return stripExceptionPrefix(normalized);
    }

    private static String stripExceptionPrefix(String reason) {
        for (String prefix : FAILURE_REASON_PREFIXES) {
            if (reason.startsWith(prefix)) {
                return Texts.trimToEmpty(reason.substring(prefix.length()));
            }
        }
        return reason;
    }

    private static boolean isChapterFailureCacheable(String reason) {
        if (!Texts.hasText(reason)) {
            return false;
        }
        return reason.contains("章节内容为空/过短")
            || reason.contains("章节内容为空")
            || reason.contains("upstream item code=")
            || reason.contains("Encrypted data too short");
    }


}
