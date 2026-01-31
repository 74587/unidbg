package com.anjia.unidbgserver.dto;

import lombok.Data;

import java.util.Map;

/**
 * FQNovel 批量获取响应
 * 对应 Rust 中的 FqIBatchFullResponse 结构
 */
@Data
public class FqIBatchFullResponse {
    
    /**
     * 响应码
     */
    private long code;
    
    /**
     * 响应消息
     */
    private String message;
    
    /**
     * 响应数据 - 章节ID到内容的映射
     */
    private Map<String, ItemContent> data;
}
