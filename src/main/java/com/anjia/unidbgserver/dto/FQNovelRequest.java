package com.anjia.unidbgserver.dto;

import lombok.Data;

/**
 * FQNovel API 请求参数
 */
@Data
public class FQNovelRequest {
    
    /**
     * 书籍ID (获取章节内容时必须)
     */
    private String bookId;
    
    /**
     * 章节ID (获取章节内容时必须)
     */
    private String chapterId;
    
    /**
     * 用户token (可选, 获取付费章节时需要)
     */
    private String token;
}
