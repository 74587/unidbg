package com.anjia.unidbgserver.utils;

import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import com.anjia.unidbgserver.dto.FQNovelData;
import com.anjia.unidbgserver.dto.ItemContent;

/**
 * 章节信息组装工具：统一章节文本、标题与元数据填充逻辑。
 */
public final class ChapterInfoBuilder {

    private ChapterInfoBuilder() {
    }

    public static FQNovelChapterInfo build(
        String bookId,
        String chapterId,
        ItemContent itemContent,
        String decryptedContent,
        boolean includeRawContent
    ) {
        String txtContent = HtmlTextExtractor.extractText(decryptedContent);

        FQNovelChapterInfo chapterInfo = new FQNovelChapterInfo();
        chapterInfo.setChapterId(chapterId);
        chapterInfo.setBookId(bookId);
        if (includeRawContent) {
            chapterInfo.setRawContent(decryptedContent);
        }
        chapterInfo.setTxtContent(txtContent);

        String title = itemContent != null ? itemContent.getTitle() : null;
        if (title == null || title.trim().isEmpty()) {
            String extractedTitle = HtmlTextExtractor.extractTitle(decryptedContent);
            title = extractedTitle != null ? extractedTitle : "章节标题";
        }
        chapterInfo.setTitle(title);

        FQNovelData novelData = itemContent != null ? itemContent.getNovelData() : null;
        chapterInfo.setAuthorName(novelData != null ? novelData.getAuthor() : "未知作者");
        chapterInfo.setWordCount(txtContent.length());
        chapterInfo.setUpdateTime(System.currentTimeMillis());

        return chapterInfo;
    }
}
