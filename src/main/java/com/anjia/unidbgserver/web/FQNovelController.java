package com.anjia.unidbgserver.web;

import com.anjia.unidbgserver.dto.FQNovelBookInfo;
import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import com.anjia.unidbgserver.dto.FQNovelRequest;
import com.anjia.unidbgserver.dto.FQNovelResponse;
import com.anjia.unidbgserver.service.FQChapterPrefetchService;
import com.anjia.unidbgserver.service.FQNovelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

/**
 * FQNovel API 控制器
 * 提供小说书籍和章节内容获取接口
 */
@Slf4j
@RestController
@RequestMapping(path = "/api/fqnovel", produces = MediaType.APPLICATION_JSON_VALUE)
public class FQNovelController {

    @Autowired
    private FQNovelService fqNovelService;

    @Autowired
    private FQChapterPrefetchService fqChapterPrefetchService;

    /**
     * 获取书籍信息
     * 
     * @param bookId 书籍ID
     * @return 书籍详情信息
     */
    @GetMapping("/book/{bookId}")
    public CompletableFuture<FQNovelResponse<FQNovelBookInfo>> getBookInfo(@PathVariable String bookId) {
        if (log.isDebugEnabled()) {
            log.debug("获取书籍信息请求 - bookId: {}", bookId);
        }
        
        if (bookId == null || bookId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("书籍ID不能为空")
            );
        }
        
        return fqNovelService.getBookInfo(bookId.trim());
    }

    /**
     * 获取章节内容 (GET方式，通过路径参数)
     * 
     * @param bookId 书籍ID
     * @param chapterId 章节ID
     * @param deviceId 设备ID (可选)
     * @param iid 安装ID（请求参数名为 iid，可选）
     * @param token 用户token (可选)
     * @return 章节内容信息
     */
    @GetMapping("/chapter/{bookId}/{chapterId}")
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContent(
            @PathVariable String bookId,
            @PathVariable String chapterId,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String iid,
            @RequestParam(required = false) String token) {
        
        if (log.isDebugEnabled()) {
            log.debug("获取章节内容请求 - bookId: {}, chapterId: {}", bookId, chapterId);
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
        request.setDeviceId(deviceId);
        request.setIid(iid);
        request.setToken(token);

        // 单章接口容易触发风控：这里做目录预取 + 缓存，减少上游调用次数
        return fqChapterPrefetchService.getChapterContent(request);
    }

    /**
     * 获取章节内容 (POST方式，通过请求体)
     * 
     * @param request 包含书籍ID、章节ID等信息的请求对象
     * @return 章节内容信息
     */
    @PostMapping("/chapter")
    public CompletableFuture<FQNovelResponse<FQNovelChapterInfo>> getChapterContentPost(
            @RequestBody FQNovelRequest request) {
        
        if (log.isDebugEnabled()) {
            log.debug("获取章节内容请求(POST) - bookId: {}, chapterId: {}", 
                request.getBookId(), request.getChapterId());
        }
        
        if (request.getBookId() == null || request.getBookId().trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("书籍ID不能为空")
            );
        }
        
        if (request.getChapterId() == null || request.getChapterId().trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                FQNovelResponse.error("章节ID不能为空")
            );
        }

        return fqChapterPrefetchService.getChapterContent(request);
    }
}
