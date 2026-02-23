package com.anjia.unidbgserver.dto;

import java.util.Map;

/**
 * FQNovel 上游 batch_full 响应
 * 对应 Rust 中的 FqIBatchFullResponse 结构
 */
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

    public long getCode() {
        return code;
    }

    public void setCode(long code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, ItemContent> getData() {
        return data;
    }

    public void setData(Map<String, ItemContent> data) {
        this.data = data;
    }
}
