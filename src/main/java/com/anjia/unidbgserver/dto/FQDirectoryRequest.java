package com.anjia.unidbgserver.dto;

/**
 * FQ书籍目录请求DTO
 */
public class FQDirectoryRequest {
    
    /**
     * 书籍ID
     */
    private String bookId;
    
    /**
     * 书籍类型
     */
    private Integer bookType = 0;
    
    /**
     * 项目数据列表MD5
     */
    private String itemDataListMd5;
    
    /**
     * 目录数据MD5
     */
    private String catalogDataMd5;
    
    /**
     * 书籍信息MD5
     */
    private String bookInfoMd5;
    
    /**
     * 是否需要版本信息
     */
    private Boolean needVersion = true;

    /**
     * 是否返回精简目录响应（仅保留 Legado 必需字段）
     * true: 仅返回 item_data_list( item_id/title ) + serial_count
     * false: 返回完整目录数据
     */
    private Boolean minimalResponse = false;

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public Integer getBookType() {
        return bookType;
    }

    public void setBookType(Integer bookType) {
        this.bookType = bookType;
    }

    public String getItemDataListMd5() {
        return itemDataListMd5;
    }

    public void setItemDataListMd5(String itemDataListMd5) {
        this.itemDataListMd5 = itemDataListMd5;
    }

    public String getCatalogDataMd5() {
        return catalogDataMd5;
    }

    public void setCatalogDataMd5(String catalogDataMd5) {
        this.catalogDataMd5 = catalogDataMd5;
    }

    public String getBookInfoMd5() {
        return bookInfoMd5;
    }

    public void setBookInfoMd5(String bookInfoMd5) {
        this.bookInfoMd5 = bookInfoMd5;
    }

    public Boolean getNeedVersion() {
        return needVersion;
    }

    public void setNeedVersion(Boolean needVersion) {
        this.needVersion = needVersion;
    }

    public Boolean getMinimalResponse() {
        return minimalResponse;
    }

    public void setMinimalResponse(Boolean minimalResponse) {
        this.minimalResponse = minimalResponse;
    }
}
