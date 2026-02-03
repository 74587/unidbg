package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.service.FqCrypto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Executor;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 单章接口的抗风控优化：
 * - 根据目录预取一段章节（调用上游 batch_full）
 * - 将结果缓存，后续单章请求直接命中缓存，显著减少上游调用次数
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FQChapterPrefetchService {

    private final FQDownloadProperties downloadProperties;
    private final FQNovelService fqNovelService;
    private final FQSearchService fqSearchService;
    private final FQRegisterKeyService registerKeyService;

    @javax.annotation.Resource(name = "applicationTaskExecutor")
    private Executor executor;

    @javax.annotation.Resource(name = "fqPrefetchExecutor")
    private Executor prefetchExecutor;

    private TimedLruCache<String, FQNovelChapterInfo> chapterCache;
    private TimedLruCache<String, List<String>> directoryCache;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> inflightPrefetch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<List<String>>> inflightDirectory = new ConcurrentHashMap<>();

    @PostConstruct
    public void initCaches() {
        int chapterMax = Math.max(1, downloadProperties.getChapterCacheMaxEntries());
        long chapterTtl = downloadProperties.getChapterCacheTtlMs();
        int dirMax = Math.max(64, chapterMax / 10);
        long dirTtl = downloadProperties.getDirectoryCacheTtlMs();

        this.chapterCache = new TimedLruCache<>(chapterMax, chapterTtl);
        this.directoryCache = new TimedLruCache<>(dirMax, dirTtl);
    }

    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(FQNovelRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String bookId = request.getBookId();
                String chapterId = request.getChapterId();
                String tokenScope = tokenScope(request.getToken());
                boolean hasToken = !"t0".equals(tokenScope);

                String cacheKey = cacheKey(bookId, chapterId, tokenScope);
                FQNovelChapterInfo cached = chapterCache.getIfPresent(cacheKey);
                if (cached == null && hasToken) {
                    cached = chapterCache.getIfPresent(cacheKey(bookId, chapterId, "t0"));
                }
                if (cached != null) {
                    return FQNovelResponse.success(cached);
                }

                // 预取：优先在目录中定位章节顺序，拉取后缓存
                prefetchAndCacheDedup(bookId, chapterId, request.getToken(), tokenScope).join();

                cached = chapterCache.getIfPresent(cacheKey);
                if (cached == null && hasToken) {
                    cached = chapterCache.getIfPresent(cacheKey(bookId, chapterId, "t0"));
                }
                if (cached != null) {
                    return FQNovelResponse.success(cached);
                }

                // 兜底：仍未命中则只取单章
                FQNovelResponse<FqIBatchFullResponse> single = fqNovelService.batchFull(chapterId, bookId, true, request.getToken()).get();
                if (single.getCode() != 0 || single.getData() == null) {
                    return FQNovelResponse.error("获取章节内容失败: " + single.getMessage());
                }

                Map<String, ItemContent> dataMap = single.getData().getData();
                if (dataMap == null || dataMap.isEmpty()) {
                    return FQNovelResponse.error("未找到章节数据");
                }

                ItemContent itemContent = dataMap.getOrDefault(chapterId, dataMap.values().iterator().next());
                FQNovelChapterInfo info = buildChapterInfo(bookId, chapterId, itemContent);
                chapterCache.put(cacheKey, info);
                return FQNovelResponse.success(info);

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                if (msg.contains("Encrypted data too short") || msg.contains("章节内容为空/过短") || msg.contains("upstream item code=")) {
                    log.warn("单章获取失败 - bookId: {}, chapterId: {}, reason={}", request.getBookId(), request.getChapterId(), msg);
                    log.debug("单章获取失败详情 - bookId: {}, chapterId: {}", request.getBookId(), request.getChapterId(), e);
                } else {
                    log.error("单章获取失败 - bookId: {}, chapterId: {}", request.getBookId(), request.getChapterId(), e);
                }
                return FQNovelResponse.error("获取章节内容失败: " + (e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        }, executor != null ? executor : ForkJoinPool.commonPool());
    }

    private CompletableFuture<Void> prefetchAndCacheDedup(String bookId, String chapterId, String token, String tokenScope) {
        String computedKey;
        try {
            computedKey = computePrefetchKey(bookId, chapterId);
        } catch (Exception e) {
            // 目录失败时退化为单章 key，仍可去重并发的同章请求
            computedKey = bookId + ":single:" + chapterId;
        }
        final String key = tokenScope + ":" + computedKey;

        CompletableFuture<Void> existing = inflightPrefetch.get(key);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Void> created = new CompletableFuture<>();
        existing = inflightPrefetch.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }

        // 注意：这里不能用与 getChapterContent 相同的线程池，否则在下载器高并发时容易出现“线程都在 join 等待”的死锁
        CompletableFuture.runAsync(() -> {
            try {
                doPrefetchAndCache(bookId, chapterId, token, tokenScope);
                created.complete(null);
            } catch (Exception e) {
                created.completeExceptionally(e);
            } finally {
                inflightPrefetch.remove(key);
            }
        }, prefetchExecutor != null ? prefetchExecutor : ForkJoinPool.commonPool());

        return created;
    }

    private String computePrefetchKey(String bookId, String chapterId) throws Exception {
        List<String> itemIds = getDirectoryItemIds(bookId);
        if (itemIds == null || itemIds.isEmpty()) {
            return bookId + ":single:" + chapterId;
        }
        int index = itemIds.indexOf(chapterId);
        if (index < 0) {
            return bookId + ":single:" + chapterId;
        }
        int size = Math.max(1, Math.min(30, downloadProperties.getChapterPrefetchSize()));
        int bucketStart = (index / size) * size;
        return bookId + ":bucket:" + bucketStart + ":" + size;
    }

    private void doPrefetchAndCache(String bookId, String chapterId, String token, String tokenScope) throws Exception {
        List<String> itemIds = getDirectoryItemIds(bookId);
        if (itemIds == null || itemIds.isEmpty()) {
            return;
        }

        int index = itemIds.indexOf(chapterId);
        List<String> batchIds;
        if (index < 0) {
            batchIds = Collections.singletonList(chapterId);
        } else {
            int size = Math.max(1, Math.min(30, downloadProperties.getChapterPrefetchSize()));
            int endExclusive = Math.min(itemIds.size(), index + size);
            batchIds = itemIds.subList(index, endExclusive);
        }

        // 拉取并解密
        String joined = String.join(",", batchIds);
        FQNovelResponse<FqIBatchFullResponse> batch = fqNovelService.batchFull(joined, bookId, true, token).get();
        if (batch.getCode() != 0 || batch.getData() == null || batch.getData().getData() == null) {
            return;
        }

        for (String itemId : batchIds) {
            ItemContent content = batch.getData().getData().get(itemId);
            if (content == null) {
                continue;
            }
            try {
                FQNovelChapterInfo info = buildChapterInfo(bookId, itemId, content);
                chapterCache.put(cacheKey(bookId, itemId, tokenScope), info);
            } catch (Exception e) {
                log.debug("预取章节处理失败 - bookId: {}, itemId: {}", bookId, itemId, e);
            }
        }
    }

    private List<String> getDirectoryItemIds(String bookId) throws Exception {
        List<String> cached = directoryCache.getIfPresent(bookId);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }

        CompletableFuture<List<String>> inFlight = inflightDirectory.get(bookId);
        if (inFlight != null) {
            try {
                return inFlight.get();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        CompletableFuture<List<String>> created = new CompletableFuture<>();
        inFlight = inflightDirectory.putIfAbsent(bookId, created);
        if (inFlight != null) {
            try {
                return inFlight.get();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }

        // 同上：目录获取也使用独立线程池，避免与业务线程互相等待
        CompletableFuture.runAsync(() -> {
            try {
                FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
                directoryRequest.setBookId(bookId);
                directoryRequest.setBookType(0);
                directoryRequest.setNeedVersion(true);

                FQNovelResponse<FQDirectoryResponse> resp = fqSearchService.getBookDirectory(directoryRequest).get();
                if (resp.getCode() != 0 || resp.getData() == null || resp.getData().getItemDataList() == null) {
                    created.complete(Collections.emptyList());
                    return;
                }

                List<String> itemIds = new ArrayList<>();
                for (FQDirectoryResponse.ItemData item : resp.getData().getItemDataList()) {
                    if (item != null && item.getItemId() != null && !item.getItemId().trim().isEmpty()) {
                        itemIds.add(item.getItemId().trim());
                    }
                }

                directoryCache.put(bookId, itemIds);
                created.complete(itemIds);
            } catch (Exception e) {
                created.complete(Collections.emptyList());
            } finally {
                inflightDirectory.remove(bookId);
            }
        }, prefetchExecutor != null ? prefetchExecutor : ForkJoinPool.commonPool());

        try {
            return created.get();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private FQNovelChapterInfo buildChapterInfo(String bookId, String chapterId, ItemContent itemContent) throws Exception {
        if (itemContent == null) {
            throw new IllegalArgumentException("章节内容为空");
        }
        if (itemContent.getCode() != 0) {
            throw new IllegalStateException("upstream item code=" + itemContent.getCode());
        }
        String encrypted = itemContent.getContent();
        if (encrypted == null || encrypted.trim().isEmpty()) {
            throw new IllegalArgumentException("章节内容为空/过短");
        }
        // Base64(16 bytes iv + ...) 的最短长度约为 24（含 padding）；过短通常是上游返回了异常内容
        if (encrypted.trim().length() < 24) {
            throw new IllegalArgumentException("章节内容为空/过短");
        }

        String decryptedContent;
        Long contentKeyver = itemContent.getKeyVersion();
        String key = registerKeyService.getDecryptionKey(contentKeyver);
        decryptedContent = FqCrypto.decryptAndDecompressContent(encrypted, key);

        String txtContent = extractTextFromHtml(decryptedContent);

        FQNovelChapterInfo chapterInfo = new FQNovelChapterInfo();
        chapterInfo.setChapterId(chapterId);
        chapterInfo.setBookId(bookId);
        chapterInfo.setRawContent(decryptedContent);
        chapterInfo.setTxtContent(txtContent);

        String title = itemContent.getTitle();
        if (title == null || title.trim().isEmpty()) {
            Pattern titlePattern = Pattern.compile("<h1[^>]*>.*?<blk[^>]*>([^<]*)</blk>.*?</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher titleMatcher = titlePattern.matcher(decryptedContent);
            if (titleMatcher.find()) {
                title = titleMatcher.group(1).trim();
            } else {
                title = "章节标题";
            }
        }
        chapterInfo.setTitle(title);

        FQNovelData novelData = itemContent.getNovelData();
        chapterInfo.setAuthorName(novelData != null ? novelData.getAuthor() : "未知作者");
        chapterInfo.setWordCount(txtContent.length());
        chapterInfo.setUpdateTime(System.currentTimeMillis());

        return chapterInfo;
    }

    private static String tokenScope(String token) {
        if (token == null) {
            return "t0";
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return "t0";
        }
        return "t" + shortSha256Hex(trimmed);
    }

    private static String cacheKey(String bookId, String chapterId, String tokenScope) {
        return tokenScope + ":" + bookId + ":" + chapterId;
    }

    private static String shortSha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // 取前 6 bytes -> 12 hex chars，足够区分且避免暴露 token
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6 && i < hash.length; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            // 兜底：极少发生，退化为“有 token”但不区分不同 token
            return "1";
        }
    }

    private String extractTextFromHtml(String htmlContent) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();
        try {
            Pattern blkPattern = Pattern.compile("<blk[^>]*>([^<]*)</blk>", Pattern.CASE_INSENSITIVE);
            Matcher matcher = blkPattern.matcher(htmlContent);
            while (matcher.find()) {
                String text = matcher.group(1);
                if (text != null && !text.trim().isEmpty()) {
                    textBuilder.append(text.trim()).append("\n");
                }
            }
            if (textBuilder.length() == 0) {
                String text = htmlContent.replaceAll("<[^>]+>", "").trim();
                if (!text.isEmpty()) {
                    textBuilder.append(text);
                }
            }
        } catch (Exception e) {
            return htmlContent.replaceAll("<[^>]+>", "").trim();
        }
        return textBuilder.toString().trim();
    }

    /**
     * 轻量 LRU + TTL 缓存（无额外依赖）。
     */
    static class TimedLruCache<K, V> {
        private final Map<K, Entry<V>> map;
        private final int maxEntries;
        private final long ttlMs;

        TimedLruCache(int maxEntries, long ttlMs) {
            this.maxEntries = Math.max(1, maxEntries);
            this.ttlMs = ttlMs;
            this.map = Collections.synchronizedMap(new LinkedHashMap<K, Entry<V>>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                    return size() > TimedLruCache.this.maxEntries;
                }
            });
        }

        V getIfPresent(K key) {
            Entry<V> entry = map.get(key);
            if (entry == null) {
                return null;
            }
            if (ttlMs > 0 && entry.expiresAtMs < System.currentTimeMillis()) {
                map.remove(key);
                return null;
            }
            return entry.value;
        }

        void put(K key, V value) {
            long expiresAt = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : Long.MAX_VALUE;
            map.put(key, new Entry<>(value, expiresAt));
        }

        static class Entry<V> {
            final V value;
            final long expiresAtMs;

            Entry(V value, long expiresAtMs) {
                this.value = value;
                this.expiresAtMs = expiresAtMs;
            }
        }

    }
}
