package com.anjia.unidbgserver.utils;

import com.anjia.unidbgserver.dto.FQNovelChapterInfo;

/**
 * 章节缓存一致性与有效性校验。
 */
public final class ChapterCacheValidator {

    private ChapterCacheValidator() {
    }

    public static boolean normalizeIdentityAndCheck(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (!Texts.hasText(bookId) || !Texts.hasText(chapterId) || chapterInfo == null) {
            return false;
        }

        String normalizedBookId = bookId.trim();
        String normalizedChapterId = chapterId.trim();

        if (!Texts.hasText(chapterInfo.getBookId())) {
            chapterInfo.setBookId(normalizedBookId);
        } else if (!normalizedBookId.equals(chapterInfo.getBookId().trim())) {
            return false;
        }

        if (!Texts.hasText(chapterInfo.getChapterId())) {
            chapterInfo.setChapterId(normalizedChapterId);
        } else if (!normalizedChapterId.equals(chapterInfo.getChapterId().trim())) {
            return false;
        }

        return true;
    }

    public static boolean isContentValid(FQNovelChapterInfo chapterInfo) {
        return chapterInfo != null
            && Texts.hasText(chapterInfo.getTitle())
            && Texts.hasText(chapterInfo.getTxtContent());
    }

    public static boolean isCacheable(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        return normalizeIdentityAndCheck(bookId, chapterId, chapterInfo) && isContentValid(chapterInfo);
    }
}
