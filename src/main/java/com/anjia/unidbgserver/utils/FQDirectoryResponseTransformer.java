package com.anjia.unidbgserver.utils;

import com.anjia.unidbgserver.dto.FQDirectoryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录响应转换：增强章节信息和最小化输出字段。
 */
public final class FQDirectoryResponseTransformer {

    private static final Logger log = LoggerFactory.getLogger(FQDirectoryResponseTransformer.class);

    private static final DateTimeFormatter CHAPTER_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SYSTEM_ZONE_ID = ZoneId.systemDefault();

    private FQDirectoryResponseTransformer() {
    }

    public static void enhanceChapterList(FQDirectoryResponse directoryResponse) {
        if (directoryResponse == null || directoryResponse.getItemDataList() == null) {
            return;
        }

        List<FQDirectoryResponse.ItemData> itemDataList = directoryResponse.getItemDataList();
        int totalChapters = itemDataList.size();

        for (int i = 0; i < totalChapters; i++) {
            FQDirectoryResponse.ItemData item = itemDataList.get(i);

            item.setChapterIndex(i + 1);
            item.setIsLatest(i == totalChapters - 1);

            setFirstPassTimeFormatted(item);

            if (item.getSortOrder() == null) {
                item.setSortOrder(i + 1);
            }

            if (item.getIsFree() == null) {
                item.setIsFree(i < 5);
            }
        }

        log.debug("章节列表增强完成 - 总章节数: {}", totalChapters);
    }

    private static void setFirstPassTimeFormatted(FQDirectoryResponse.ItemData item) {
        if (item == null) {
            return;
        }
        Integer firstPassTime = item.getFirstPassTime();
        if (firstPassTime == null || firstPassTime <= 0) {
            return;
        }
        try {
            String timeStr = Instant.ofEpochSecond(firstPassTime.longValue())
                .atZone(SYSTEM_ZONE_ID)
                .format(CHAPTER_TIME_FORMATTER);
            item.setFirstPassTimeStr(timeStr);
        } catch (Exception e) {
            log.warn("格式化时间失败 - timestamp: {}", firstPassTime, e);
        }
    }

    public static void trimForMinimalResponse(FQDirectoryResponse directoryResponse) {
        if (directoryResponse == null) {
            return;
        }

        List<FQDirectoryResponse.ItemData> source = directoryResponse.getItemDataList();
        List<FQDirectoryResponse.ItemData> minimal = new ArrayList<>(source == null ? 0 : source.size());
        if (source != null) {
            for (FQDirectoryResponse.ItemData item : source) {
                FQDirectoryResponse.ItemData reduced = toMinimalItem(item);
                if (reduced != null) {
                    minimal.add(reduced);
                }
            }
        }

        directoryResponse.setItemDataList(minimal);
        Integer upstreamSerialCount = directoryResponse.getSerialCount();
        int finalSerialCount = upstreamSerialCount == null ? minimal.size() : Math.max(minimal.size(), upstreamSerialCount);
        directoryResponse.setSerialCount(finalSerialCount);
        directoryResponse.setBookInfo(null);
        directoryResponse.setCatalogData(null);
        directoryResponse.setFieldCacheStatus(null);
        directoryResponse.setBanRecover(null);
        directoryResponse.setAdditionalItemDataList(null);

        log.debug("目录响应裁剪完成 - 章节数: {}", minimal.size());
    }

    private static FQDirectoryResponse.ItemData toMinimalItem(FQDirectoryResponse.ItemData item) {
        if (item == null) {
            return null;
        }
        String itemId = Texts.trimToNull(item.getItemId());
        if (itemId == null) {
            return null;
        }
        FQDirectoryResponse.ItemData reduced = new FQDirectoryResponse.ItemData();
        reduced.setItemId(itemId);
        reduced.setTitle(Texts.trimToEmpty(item.getTitle()));
        return reduced;
    }
}
