package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.service.FQSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * FQ书籍搜索和目录控制器（精简版，仅支持 Legado 阅读）
 * 提供书籍搜索、目录获取等API接口
 */
@Slf4j
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class FQSearchController {

    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 100;
    private static final int MAX_TAB_TYPE = 20;

    @Autowired
    private FQSearchService fqSearchService;

    /**
     * 搜索书籍
     * 路径: /search?key={关键词}&page=1&size=20&tabType=3
     *
     * @param key 搜索关键词
     * @param page 页码（从1开始，默认1）
     * @param size 每页数量（默认20）
     * @param tabType 搜索类型（默认3）
     * @param searchId 搜索ID（可选，用于翻页）
     * @return 搜索结果
     */
    @GetMapping("/search")
    public CompletableFuture<FQNovelResponse<FQSearchResponseSimple>> searchBooks(
            @RequestParam String key,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size,
            @RequestParam(defaultValue = "3") Integer tabType,
            @RequestParam(required = false) String searchId) {

        if (log.isDebugEnabled()) {
            log.debug("搜索书籍 - key: {}, page: {}, size: {}, tabType: {}", key, page, size, tabType);
        }

        String trimmedKey = key == null ? "" : key.trim();
        if (trimmedKey.isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.<FQSearchResponseSimple>error("搜索关键词不能为空")
            );
        }
        if (trimmedKey.length() > MAX_QUERY_LENGTH) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.<FQSearchResponseSimple>error("搜索关键词过长")
            );
        }
        if (page == null || page < 1) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.<FQSearchResponseSimple>error("页码必须大于等于1")
            );
        }
        if (size == null || size < 1 || size > MAX_PAGE_SIZE) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.<FQSearchResponseSimple>error("size 超出范围（1-50）")
            );
        }
        if (tabType == null || tabType < 1 || tabType > MAX_TAB_TYPE) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.<FQSearchResponseSimple>error("tabType 超出范围")
            );
        }

        // 将页码转换为offset（页码从1开始，offset从0开始）
        long offsetLong = ((long) page - 1L) * size;
        if (offsetLong > Integer.MAX_VALUE) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.<FQSearchResponseSimple>error("分页参数过大")
            );
        }
        int offset = (int) offsetLong;

        // 构建搜索请求
        FQSearchRequest searchRequest = new FQSearchRequest();
        searchRequest.setQuery(trimmedKey);
        searchRequest.setOffset(offset);
        searchRequest.setCount(size);
        searchRequest.setTabType(tabType);
        searchRequest.setSearchId(searchId != null ? searchId.trim() : null);
        searchRequest.setPassback(offset);

        return fqSearchService.searchBooksEnhanced(searchRequest)
            .thenApply(response -> {
                if (response == null) {
                    return FQNovelResponse.<FQSearchResponseSimple>error("搜索失败: 空响应");
                }
                if (response.getCode() == null) {
                    return FQNovelResponse.<FQSearchResponseSimple>error("搜索失败: 响应码为空");
                }
                if (response.getCode() != 0) {
                    return FQNovelResponse.<FQSearchResponseSimple>error(response.getCode(), response.getMessage());
                }
                FQSearchResponseSimple simple = FQSearchResponseSimple.fromFull(response.getData());
                return FQNovelResponse.success(simple);
            });
    }

    /**
     * 获取书籍目录
     * 路径: /toc/{bookId}
     *
     * @param bookId 书籍ID
     * @return 书籍目录
     */
    @GetMapping("/toc/{bookId}")
    public CompletableFuture<FQNovelResponse<FQDirectoryResponse>> getBookToc(
            @PathVariable String bookId) {

        if (log.isDebugEnabled()) {
            log.debug("获取书籍目录 - bookId: {}", bookId);
        }

        if (bookId == null || bookId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("书籍ID不能为空")
            );
        }

        // 构建目录请求
        FQDirectoryRequest directoryRequest = new FQDirectoryRequest();
        directoryRequest.setBookId(bookId.trim());
        directoryRequest.setMinimalResponse(true);

        return fqSearchService.getBookDirectory(directoryRequest);
    }
}
