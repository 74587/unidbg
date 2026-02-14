package com.anjia.unidbgserver.dto;

import com.anjia.unidbgserver.json.LenientIntegerDeserializer;
import com.anjia.unidbgserver.json.LenientLongDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

/**
 * 目录接口中的书籍信息（仅保留当前映射需要字段）。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FQNovelBookInfoResp {

    @JsonProperty("book_name")
    private String bookName;

    private String author;

    @JsonProperty("abstract")
    private String abstractContent;

    @JsonProperty("book_abstract_v2")
    private String bookAbstractV2;

    @JsonProperty("thumb_url")
    private String thumbUrl;

    @JsonProperty("word_number")
    @JsonDeserialize(using = LenientLongDeserializer.class)
    private Long wordNumber;

    @JsonProperty("last_chapter_title")
    private String lastChapterTitle;

    private String category;

    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    private Integer status;

    @JsonProperty("serial_count")
    @JsonDeserialize(using = LenientIntegerDeserializer.class)
    private Integer serialCount;
}
