package com.anjia.unidbgserver.dto;

import java.util.Map;

/**
 * FQNovel 上游 batch_full 响应
 * 对应 Rust 中的 FqIBatchFullResponse 结构
 */
public record FqIBatchFullResponse(
    long code,
    String message,
    Map<String, ItemContent> data
) {
}
