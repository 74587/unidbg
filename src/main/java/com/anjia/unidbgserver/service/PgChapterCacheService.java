package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.FQNovelChapterInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

/**
 * PostgreSQL 章节缓存：持久化 bookId/chapterId 对应的章节响应。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnBean(JdbcTemplate.class)
public class PgChapterCacheService {

    private static final String CREATE_TABLE_SQL =
        "CREATE TABLE IF NOT EXISTS chapter ("
            + "book_id VARCHAR(64) NOT NULL,"
            + "chapter_id VARCHAR(64) NOT NULL,"
            + "payload JSONB NOT NULL,"
            + "updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),"
            + "PRIMARY KEY (book_id, chapter_id)"
            + ")";

    private static final String CREATE_UPDATED_INDEX_SQL =
        "CREATE INDEX IF NOT EXISTS chapter_idx ON chapter(updated_at)";

    private static final String SELECT_SQL =
        "SELECT payload::text FROM chapter WHERE book_id = ? AND chapter_id = ? LIMIT 1";

    private static final String UPSERT_SQL =
        "INSERT INTO chapter (book_id, chapter_id, payload, updated_at) "
            + "VALUES (?, ?, CAST(? AS jsonb), now()) "
            + "ON CONFLICT (book_id, chapter_id) "
            + "DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()";

    private static final String DELETE_SQL =
        "DELETE FROM chapter WHERE book_id = ? AND chapter_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void initSchema() {
        try {
            jdbcTemplate.execute(CREATE_TABLE_SQL);
            jdbcTemplate.execute(CREATE_UPDATED_INDEX_SQL);
            log.info("PostgreSQL 章节缓存表已就绪");
        } catch (Exception e) {
            log.error("初始化 PostgreSQL 章节缓存表失败", e);
            throw e;
        }
    }

    public FQNovelChapterInfo getChapter(String bookId, String chapterId) {
        if (!hasText(bookId) || !hasText(chapterId)) {
            return null;
        }

        String normalizedBookId = bookId.trim();
        String normalizedChapterId = chapterId.trim();

        try {
            String payload = jdbcTemplate.query(
                SELECT_SQL,
                ps -> {
                    ps.setString(1, normalizedBookId);
                    ps.setString(2, normalizedChapterId);
                },
                rs -> rs.next() ? rs.getString(1) : null
            );

            if (!hasText(payload)) {
                return null;
            }

            try {
                FQNovelChapterInfo chapterInfo = objectMapper.readValue(payload, FQNovelChapterInfo.class);
                if (!normalizeIdentityAndCheck(normalizedBookId, normalizedChapterId, chapterInfo)
                    || !isContentValid(chapterInfo)) {
                    deleteQuietly(normalizedBookId, normalizedChapterId);
                    return null;
                }
                return chapterInfo;
            } catch (Exception parseEx) {
                deleteQuietly(normalizedBookId, normalizedChapterId);
                log.warn("PostgreSQL 章节缓存解析失败，已清理坏数据 - bookId: {}, chapterId: {}",
                    normalizedBookId, normalizedChapterId, parseEx);
                return null;
            }
        } catch (Exception e) {
            log.warn("读取 PostgreSQL 章节缓存失败 - bookId: {}, chapterId: {}", normalizedBookId, normalizedChapterId, e);
            return null;
        }
    }

    /**
     * 仅在章节内容有效时写入缓存。
     */
    public void saveChapterIfValid(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (!hasText(bookId) || !hasText(chapterId) || chapterInfo == null) {
            return;
        }

        String normalizedBookId = bookId.trim();
        String normalizedChapterId = chapterId.trim();

        if (!normalizeIdentityAndCheck(normalizedBookId, normalizedChapterId, chapterInfo) || !isContentValid(chapterInfo)) {
            if (log.isDebugEnabled()) {
                log.debug("跳过写入 PostgreSQL 缓存（章节数据无效）- bookId: {}, chapterId: {}", normalizedBookId, normalizedChapterId);
            }
            return;
        }

        try {
            String payload = objectMapper.writeValueAsString(chapterInfo);
            jdbcTemplate.update(UPSERT_SQL, normalizedBookId, normalizedChapterId, payload);
        } catch (Exception e) {
            log.warn("写入 PostgreSQL 章节缓存失败 - bookId: {}, chapterId: {}", normalizedBookId, normalizedChapterId, e);
        }
    }

    private void deleteQuietly(String bookId, String chapterId) {
        try {
            jdbcTemplate.update(DELETE_SQL, bookId, chapterId);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static boolean normalizeIdentityAndCheck(String bookId, String chapterId, FQNovelChapterInfo chapterInfo) {
        if (chapterInfo == null) {
            return false;
        }

        if (!hasText(chapterInfo.getBookId())) {
            chapterInfo.setBookId(bookId);
        } else if (!bookId.equals(chapterInfo.getBookId().trim())) {
            return false;
        }

        if (!hasText(chapterInfo.getChapterId())) {
            chapterInfo.setChapterId(chapterId);
        } else if (!chapterId.equals(chapterInfo.getChapterId().trim())) {
            return false;
        }

        return true;
    }

    private static boolean isContentValid(FQNovelChapterInfo chapterInfo) {
        return chapterInfo != null
            && hasText(chapterInfo.getTitle())
            && hasText(chapterInfo.getTxtContent());
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
