package com.anjia.unidbgserver.mapper;

import com.anjia.unidbgserver.dto.FQNovelBookInfo;
import com.anjia.unidbgserver.dto.FQNovelBookInfoResp;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

/**
 * BookInfo 对象映射器
 * 使用 MapStruct 简化对象映射逻辑
 */
@Mapper(componentModel = "spring")
public interface BookInfoMapper {

    BookInfoMapper INSTANCE = Mappers.getMapper(BookInfoMapper.class);

    /**
     * 将 FQNovelBookInfoResp 映射为 FQNovelBookInfo
     * 
     * @param resp 原始响应对象
     * @param bookId 书籍ID
     * @return 映射后的书籍信息对象
     */
    @Mapping(target = "bookId", source = "bookId")
    @Mapping(target = "description", source = "resp.abstractContent")
    @Mapping(target = "status", source = "resp.status")
    @Mapping(target = "totalChapters", ignore = true) // 需要特殊处理
    @Mapping(target = "authorInfo", ignore = true) // 需要特殊处理
    FQNovelBookInfo toBookInfo(FQNovelBookInfoResp resp, String bookId);
}
