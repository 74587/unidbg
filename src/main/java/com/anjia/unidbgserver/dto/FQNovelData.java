package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * FQ小说数据DTO - 精简版（仅保留实际使用的字段）
 * 对应API响应中的novel_data字段
 * 
 * 注意：上游API返回100+个字段，但我们只需要author字段
 * 使用 @JsonIgnoreProperties(ignoreUnknown = true) 忽略其他字段
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class FQNovelData {



    /**
     * 作者名称（唯一实际使用的字段）
     */
    @JsonProperty("author")
    private String author;
}
