package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FQNovel 章节内容项
 * 对应 Rust 中的 ItemContent 结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ItemContent(
    long code,
    String title,
    String content,
    @JsonProperty("novel_data") FQNovelData novelData,
    @JsonProperty("text_type") long textType,
    @JsonProperty("crypt_status") long cryptStatus,
    @JsonProperty("compress_status") long compressStatus,
    @JsonProperty("key_version") long keyVersion,
    @JsonProperty("paragraphs_num") long paragraphsNum,
    @JsonProperty("author_speak") String authorSpeak
) {
}
