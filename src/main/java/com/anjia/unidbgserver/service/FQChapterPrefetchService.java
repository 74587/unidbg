package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.config.FQDownloadProperties;
import com.anjia.unidbgserver.dto.*;
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

    // 修复问题 #7：定义魔法数字为常量
    /**
     * Base64 编码的最小有效长度（16 bytes IV + 至少1 byte数据 = 24 chars）
     */
    private static final int MIN_BASE64_ENCRYPTED_LENGTH = 24;
    
    /**
     * Token hash 使用的字节数（16 bytes = 32 hex chars，碰撞概率 2^-128）
     */
    private static final int TOKEN_HASH_BYTES = 16;
    
    /**
     * HTML 文本提取的正则表达式（编译为静态常量以提高性能）
     */
    private static final Pattern BLK_PATTERN = Pattern.compile("<blk[^>]*>([^<]*)</blk>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<h1[^>]*>.*?<blk[^>]*>([^<]*)</blk>.*?</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final FQDownloadProperties downloadProperties;
    private final FQNovelService fqNovelService;
    private final FQSearchService fqSearchService;
    private final FQRegisterKeyService registerKeyService;

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
        if (request == null) {
            return CompletableFuture.completedFuture(FQNovelResponse.<FQNovelChapterInfo>error("请求不能为空"));
        }

        String rawBookId = request.getBookId();
        if (rawBookId == null || rawBookId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(FQNovelResponse.<FQNovelChapterInfo>error("书籍ID不能为空"));
        }
        final String bookId = rawBookId.trim();

        String rawChapterId = request.getChapterId();
        if (rawChapterId == null || rawChapterId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(FQNovelResponse.<FQNovelChapterInfo>error("章节ID不能为空"));
        }
        final String chapterId = rawChapterId.trim();

        final String token = request.getToken();
        final String tokenScope = tokenScope(token);

        FQNovelChapterInfo cached = getCachedChapter(bookId, chapterId, tokenScope);
        if (cached != null) {
            return CompletableFuture.completedFuture(FQNovelResponse.success(cached));
        }

        // 预取：优先在目录中定位章节顺序，拉取后缓存（非阻塞链式调用，避免线程池互等死锁）
        return prefetchAndCacheDedup(bookId, chapterId, token, tokenScope)
            .exceptionally(ex -> null) // 预取失败不影响单章兜底
            .thenCompose(ignored -> {
                FQNovelChapterInfo afterPrefetch = getCachedChapter(bookId, chapterId, tokenScope);
                if (afterPrefetch != null) {
                    return CompletableFuture.completedFuture(FQNovelResponse.success(afterPrefetch));
                }

                // 兜底：仍未命中则只取单章
                return fqNovelService.batchFull(chapterId, bookId, true, token).thenApply(single -> {
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
                        chapterCache.put(cacheKey(bookId, chapterId, tokenScope), info);
                        return FQNovelResponse.success(info);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            })
            .exceptionally(e -> {
                Throwable t = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                if (msg.contains("Encrypted data too short") || msg.contains("章节内容为空/过短") || msg.contains("upstream item code=")) {
                    log.warn("单章获取失败 - bookId: {}, chapterId: {}, reason={}", bookId, chapterId, msg);
                    log.debug("单章获取失败详情 - bookId: {}, chapterId: {}", bookId, chapterId, t);
                } else {
                    log.error("单章获取失败 - bookId: {}, chapterId: {}", bookId, chapterId, t);
                }
                return FQNovelResponse.<FQNovelChapterInfo>error("获取章节内容失败: " + msg);
            });
    }

    private CompletableFuture<Void> prefetchAndCacheDedup(String bookId, String chapterId, String token, String tokenScope) {
        final String key = tokenScope + ":" + computePrefetchKeyFast(bookId, chapterId);

        CompletableFuture<Void> existing = inflightPrefetch.get(key);
        if (existing != null) {
            return existing;
        }

        CompletableFuture<Void> created = new CompletableFuture<>();
        existing = inflightPrefetch.putIfAbsent(key, created);
        if (existing != null) {
            return existing;
        }

        doPrefetchAndCacheAsync(bookId, chapterId, token, tokenScope).whenComplete((v, ex) -> {
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
        List<String> itemIds = directoryCache.getIfPresent(bookId);
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

    private CompletableFuture<Void> doPrefetchAndCacheAsync(String bookId, String chapterId, String token, String tokenScope) {
        Executor exec = prefetchExecutor != null ? prefetchExecutor : ForkJoinPool.commonPool();
        return getDirectoryItemIdsAsync(bookId).thenCompose(itemIds -> {
            if (itemIds == null || itemIds.isEmpty()) {
                return CompletableFuture.completedFuture(null);
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

            // 拉取并解密（处理放在 prefetchExecutor 上，避免占用业务线程池）
            String joined = String.join(",", batchIds);
            return fqNovelService.batchFull(joined, bookId, true, token).thenAcceptAsync(batch -> {
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
                        chapterCache.put(cacheKey(bookId, itemId, tokenScope), info);
                    } catch (Exception e) {
                        log.debug("预取章节处理失败 - bookId: {}, itemId: {}", bookId, itemId, e);
                    }
                }
            }, exec);
        });
    }

    private CompletableFuture<List<String>> getDirectoryItemIdsAsync(String bookId) {
        List<String> cached = directoryCache.getIfPresent(bookId);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        CompletableFuture<List<String>> inFlight = inflightDirectory.get(bookId);
        if (inFlight != null) {
            return inFlight;
        }

        CompletableFuture<List<String>> created = new CompletableFuture<>();
        inFlight = inflightDirectory.putIfAbsent(bookId, created);
        if (inFlight != null) {
            return inFlight;
        }

        try {
            FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
            directoryRequest.setBookId(bookId);
            directoryRequest.setBookType(0);
            directoryRequest.setNeedVersion(true);

            fqSearchService.getBookDirectory(directoryRequest)
                .handle((resp, ex) -> {
                    if (ex != null || resp == null || resp.getCode() != 0 || resp.getData() == null || resp.getData().getItemDataList() == null) {
                        return Collections.<String>emptyList();
                    }

                    List<String> itemIds = new ArrayList<>();
                    for (FQDirectoryResponse.ItemData item : resp.getData().getItemDataList()) {
                        if (item != null && item.getItemId() != null && !item.getItemId().trim().isEmpty()) {
                            itemIds.add(item.getItemId().trim());
                        }
                    }

                    directoryCache.put(bookId, itemIds);
                    return itemIds;
                })
                .whenComplete((itemIds, ex) -> {
                    created.complete(itemIds != null ? itemIds : Collections.<String>emptyList());
                    inflightDirectory.remove(bookId, created);
                });
        } catch (Exception e) {
            created.complete(Collections.emptyList());
            inflightDirectory.remove(bookId, created);
        }

        return created;
    }

    private FQNovelChapterInfo getCachedChapter(String bookId, String chapterId, String tokenScope) {
        String key = cacheKey(bookId, chapterId, tokenScope);
        return chapterCache.getIfPresent(key);
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
        if (encrypted.trim().length() < MIN_BASE64_ENCRYPTED_LENGTH) {
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
            // 修复问题 #12：使用预编译的静态正则表达式，提高性能
            Matcher titleMatcher = TITLE_PATTERN.matcher(decryptedContent);
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
            int bytes = Math.min(TOKEN_HASH_BYTES, hash.length);
            // 取前 N bytes -> 2N hex chars，足够区分且避免暴露完整 token
            StringBuilder sb = new StringBuilder(bytes * 2);
            for (int i = 0; i < bytes; i++) {
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
            // 修复问题 #12：使用预编译的静态正则表达式，提高性能
            Matcher matcher = BLK_PATTERN.matcher(htmlContent);
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
