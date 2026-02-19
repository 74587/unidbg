package com.anjia.unidbgserver.utils;

import com.anjia.unidbgserver.dto.FQDirectoryResponse;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 目录响应转换：增强章节信息和最小化输出字段。
 */
@Slf4j
public final class FQDirectoryResponseTransformer {

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

            if (item.getFirstPassTime() != null && item.getFirstPassTime() > 0) {
                try {
                    String timeStr = Instant.ofEpochSecond(item.getFirstPassTime())
                        .atZone(SYSTEM_ZONE_ID)
                        .format(CHAPTER_TIME_FORMATTER);
                    item.setFirstPassTimeStr(timeStr);
                } catch (Exception e) {
                    log.warn("格式化时间失败 - timestamp: {}", item.getFirstPassTime(), e);
                }
            }

            if (item.getSortOrder() == null) {
                item.setSortOrder(i + 1);
            }

            if (item.getIsFree() == null) {
                item.setIsFree(i < 5);
            }
        }

        log.debug("章节列表增强完成 - 总章节数: {}", totalChapters);
    }

    public static void trimForMinimalResponse(FQDirectoryResponse directoryResponse) {
        if (directoryResponse == null) {
            return;
        }

        List<FQDirectoryResponse.ItemData> source = directoryResponse.getItemDataList();
        List<FQDirectoryResponse.ItemData> minimal = new ArrayList<>();
        if (source != null) {
            minimal = new ArrayList<>(source.size());
            for (FQDirectoryResponse.ItemData item : source) {
                if (item == null) {
                    continue;
                }
                String itemId = item.getItemId() != null ? item.getItemId().trim() : "";
                if (itemId.isEmpty()) {
                    continue;
                }
                FQDirectoryResponse.ItemData reduced = new FQDirectoryResponse.ItemData();
                reduced.setItemId(itemId);
                String title = item.getTitle() != null ? item.getTitle().trim() : "";
                reduced.setTitle(title);
                minimal.add(reduced);
            }
        }

        directoryResponse.setItemDataList(minimal);
        Integer upstreamSerialCount = directoryResponse.getSerialCount();
        int finalSerialCount = minimal.size();
        if (upstreamSerialCount != null && upstreamSerialCount > finalSerialCount) {
            finalSerialCount = upstreamSerialCount;
        }
        directoryResponse.setSerialCount(finalSerialCount);
        directoryResponse.setBookInfo(null);
        directoryResponse.setCatalogData(null);
        directoryResponse.setFieldCacheStatus(null);
        directoryResponse.setBanRecover(null);
        directoryResponse.setAdditionalItemDataList(null);

        log.debug("目录响应裁剪完成 - 章节数: {}", minimal.size());
    }
}
