package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.dto.*;
import com.anjia.unidbgserver.service.FQChapterPrefetchService;
import com.anjia.unidbgserver.service.FQNovelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * FQNovel API 控制器（精简版，仅支持 Legado 阅读）
 * 提供小说书籍和章节内容获取接口
 */
@Slf4j
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class FQNovelController {

    @Autowired
    private FQNovelService fqNovelService;

    @Autowired
    private FQChapterPrefetchService fqChapterPrefetchService;

    /**
     * 获取书籍详情（精简版 - 仅返回 Legado 需要的字段）
     * 路径: /book/{bookId}
     * 
     * @param bookId 书籍ID
     * @return 书籍详情信息（精简）
     */
    @GetMapping("/book/{bookId}")
    public CompletableFuture<FQNovelResponse<FQNovelBookInfo>> getBookInfo(@PathVariable String bookId) {
        if (log.isDebugEnabled()) {
            log.debug("获取书籍信息 - bookId: {}", bookId);
        }
        
        if (bookId == null || bookId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("书籍ID不能为空")
            );
        }

        return fqNovelService.getBookInfo(bookId.trim());
    }

    /**
     * 获取章节正文
     * 路径: /chapter/{bookId}/{chapterId}
     * 
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @return 章节内容信息
     */
    @GetMapping("/chapter/{bookId}/{chapterId}")
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(
            @PathVariable String bookId,
            @PathVariable String chapterId) {
        
        if (log.isDebugEnabled()) {
            log.debug("获取章节内容 - bookId: {}, chapterId: {}", bookId, chapterId);
        }
        
        if (bookId == null || bookId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("书籍ID不能为空")
            );
        }
        
        if (chapterId == null || chapterId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("章节ID不能为空")
            );
        }
        
        // 构建请求对象
        FQNovelRequest request = new FQNovelRequest();
        request.setBookId(bookId.trim());
        request.setChapterId(chapterId.trim());

        // 单章接口容易触发风控：这里做目录预取 + 缓存，减少上游调用次数
        return fqChapterPrefetchService.getChapterContent(request);
    }
}
