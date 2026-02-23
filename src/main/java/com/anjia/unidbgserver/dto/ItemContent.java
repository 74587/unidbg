package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * FQNovel 章节内容项
 * 对应 Rust 中的 ItemContent 结构
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ItemContent {

    /**
     * 响应码
     */
    private long code;

    /**
     * 章节标题
     */
    private String title;

    /**
     * 章节内容 (加密的)
     */
    private String content;

    /**
     * 小说数据
     */
    @JsonProperty("novel_data")
    private FQNovelData novelData;

    /**
     * 文本类型
     */
    @JsonProperty("text_type")
    private long textType;

    /**
     * 加密状态
     */
    @JsonProperty("crypt_status")
    private long cryptStatus;

    /**
     * 压缩状态
     */
    @JsonProperty("compress_status")
    private long compressStatus;

    /**
     * 密钥版本
     */
    @JsonProperty("key_version")
    private long keyVersion;

    /**
     * 段落数
     */
    @JsonProperty("paragraphs_num")
    private long paragraphsNum;

    /**
     * 作者发言
     */
    @JsonProperty("author_speak")
    private String authorSpeak;

    public long getCode() {
        return code;
    }

    public void setCode(long code) {
        this.code = code;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public FQNovelData getNovelData() {
        return novelData;
    }

    public void setNovelData(FQNovelData novelData) {
        this.novelData = novelData;
    }

    public long getTextType() {
        return textType;
    }

    public void setTextType(long textType) {
        this.textType = textType;
    }

    public long getCryptStatus() {
        return cryptStatus;
    }

    public void setCryptStatus(long cryptStatus) {
        this.cryptStatus = cryptStatus;
    }

    public long getCompressStatus() {
        return compressStatus;
    }

    public void setCompressStatus(long compressStatus) {
        this.compressStatus = compressStatus;
    }

    public long getKeyVersion() {
        return keyVersion;
    }

    public void setKeyVersion(long keyVersion) {
        this.keyVersion = keyVersion;
    }

    public long getParagraphsNum() {
        return paragraphsNum;
    }

    public void setParagraphsNum(long paragraphsNum) {
        this.paragraphsNum = paragraphsNum;
    }

    public String getAuthorSpeak() {
        return authorSpeak;
    }

    public void setAuthorSpeak(String authorSpeak) {
        this.authorSpeak = authorSpeak;
    }
}
