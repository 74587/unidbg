package com.anjia.unidbgserver.dto;

import lombok.Data;

/**
 * FQ书籍目录请求DTO
 */
@Data
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
}
