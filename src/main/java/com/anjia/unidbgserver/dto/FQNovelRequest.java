package com.anjia.unidbgserver.dto;

/**
 * FQNovel API 请求参数
 */
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
     * 设备ID
     */
    private String deviceId;
    
    /**
     * 安装ID（请求参数名为 iid）
     */
    private String iid;

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public String getChapterId() {
        return chapterId;
    }

    public void setChapterId(String chapterId) {
        this.chapterId = chapterId;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(String deviceId) {
        this.deviceId = deviceId;
    }

    public String getIid() {
        return iid;
    }

    public void setIid(String iid) {
        this.iid = iid;
    }
}
