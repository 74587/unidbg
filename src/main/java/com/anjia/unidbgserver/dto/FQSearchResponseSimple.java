package com.anjia.unidbgserver.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索响应（精简版 - 仅保留 Legado 需要字段）
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FQSearchResponseSimple {

    private List<BookItem> books;
    private Integer total;
    private Boolean hasMore;
    private String searchId;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BookItem {
        private String bookId;
        private String bookName;
        private String author;
        private String description;
        private String coverUrl;
        private String lastChapterTitle;
        private String category;
        private Long wordCount;
    }

    public static FQSearchResponseSimple fromFull(FQSearchResponse full) {
        if (full == null) {
            return null;
        }

        FQSearchResponseSimple simple = new FQSearchResponseSimple();
        simple.setTotal(full.getTotal());
        simple.setHasMore(full.getHasMore());
        simple.setSearchId(full.getSearchId());

        List<BookItem> outBooks = new ArrayList<>();
        if (full.getBooks() != null) {
            for (FQSearchResponse.BookItem item : full.getBooks()) {
                if (item == null) {
                    continue;
                }
                BookItem out = new BookItem();
                out.setBookId(item.getBookId());
                out.setBookName(item.getBookName());
                out.setAuthor(item.getAuthor());
                out.setDescription(item.getDescription());
                out.setCoverUrl(item.getCoverUrl());
                out.setLastChapterTitle(item.getLastChapterTitle());
                out.setCategory(item.getCategory());
                out.setWordCount(item.getWordCount());
                outBooks.add(out);
            }
        }
        simple.setBooks(outBooks);
        return simple;
    }
}
