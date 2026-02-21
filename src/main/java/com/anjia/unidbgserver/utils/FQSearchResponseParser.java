package com.anjia.unidbgserver.utils;

import com.anjia.unidbgserver.dto.FQSearchResponse;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索响应解析器：从上游多种结构中提取统一的搜索结果。
 */
public final class FQSearchResponseParser {

    private FQSearchResponseParser() {
    }

    public static FQSearchResponse parseSearchResponse(JsonNode jsonResponse, int tabType) {
        FQSearchResponse searchResponse = new FQSearchResponse();

        JsonNode dataNode = jsonResponse != null ? jsonResponse.path("data") : null;
        JsonNode searchTabs = jsonResponse != null ? jsonResponse.get("search_tabs") : null;
        if (searchTabs == null || !searchTabs.isArray()) {
            searchTabs = dataNode != null ? dataNode.get("search_tabs") : null;
        }
        if (searchTabs == null || !searchTabs.isArray()) {
            searchTabs = jsonResponse != null ? jsonResponse.get("searchTabs") : null;
        }
        if (searchTabs == null || !searchTabs.isArray()) {
            searchTabs = dataNode != null ? dataNode.get("searchTabs") : null;
        }

        boolean matchedTab = false;
        if (searchTabs != null && searchTabs.isArray()) {
            for (JsonNode tab : searchTabs) {
                if (tab.has("tab_type") && tab.get("tab_type").asInt() == tabType) {
                    matchedTab = true;
                    List<FQSearchResponse.BookItem> books = new ArrayList<>();
                    JsonNode tabData = tab.get("data");
                    if (tabData != null && tabData.isArray()) {
                        for (JsonNode cell : tabData) {
                            JsonNode bookData = cell.get("book_data");
                            if (bookData != null && bookData.isArray()) {
                                for (JsonNode bookNode : bookData) {
                                    books.add(parseBookItem(bookNode));
                                }
                            }
                        }
                    }
                    JsonNode directBooks = tab.get("books");
                    if (books.isEmpty() && directBooks != null && directBooks.isArray()) {
                        for (JsonNode bookNode : directBooks) {
                            books.add(parseBookItem(bookNode));
                        }
                    }
                    searchResponse.setBooks(books);

                    searchResponse.setTotal(tab.path("total").asInt(books.size()));
                    searchResponse.setHasMore(tab.path("has_more").asBoolean(false));
                    String tabSearchId = Texts.firstNonBlank(
                        tab.path("search_id").asText(""),
                        tab.path("searchId").asText(""),
                        tab.path("search_id_str").asText(""),
                        dataNode != null ? dataNode.path("search_id").asText("") : "",
                        dataNode != null ? dataNode.path("searchId").asText("") : "",
                        dataNode != null ? dataNode.path("search_id_str").asText("") : "",
                        jsonResponse != null ? jsonResponse.path("search_id").asText("") : "",
                        jsonResponse != null ? jsonResponse.path("searchId").asText("") : ""
                    );
                    searchResponse.setSearchId(tabSearchId);
                    break;
                }
            }
        }

        if (!matchedTab && (searchResponse.getBooks() == null || searchResponse.getBooks().isEmpty()) && searchTabs != null && searchTabs.isArray()) {
            for (JsonNode tab : searchTabs) {
                List<FQSearchResponse.BookItem> books = new ArrayList<>();

                JsonNode tabData = tab.get("data");
                if (tabData != null && tabData.isArray()) {
                    for (JsonNode cell : tabData) {
                        JsonNode bookData = cell.get("book_data");
                        if (bookData != null && bookData.isArray()) {
                            for (JsonNode bookNode : bookData) {
                                books.add(parseBookItem(bookNode));
                            }
                        }
                    }
                }
                JsonNode directBooks = tab.get("books");
                if (books.isEmpty() && directBooks != null && directBooks.isArray()) {
                    for (JsonNode bookNode : directBooks) {
                        books.add(parseBookItem(bookNode));
                    }
                }

                if (!books.isEmpty()) {
                    searchResponse.setBooks(books);
                    if (searchResponse.getTotal() == null) {
                        searchResponse.setTotal(tab.path("total").asInt(books.size()));
                    }
                    if (searchResponse.getHasMore() == null) {
                        Boolean hm = boolFromNode(tab.path("has_more"));
                        searchResponse.setHasMore(Boolean.TRUE.equals(hm));
                    }
                    if (Texts.isBlank(searchResponse.getSearchId())) {
                        String tabSearchId = Texts.firstNonBlank(
                            tab.path("search_id").asText(""),
                            tab.path("searchId").asText(""),
                            tab.path("search_id_str").asText("")
                        );
                        searchResponse.setSearchId(tabSearchId);
                    }
                    break;
                }
            }
        }

        if (searchResponse.getBooks() == null || searchResponse.getBooks().isEmpty()) {
            JsonNode booksNode = null;
            if (dataNode != null && dataNode.path("books").isArray()) {
                booksNode = dataNode.path("books");
            } else if (jsonResponse != null && jsonResponse.path("books").isArray()) {
                booksNode = jsonResponse.path("books");
            }

            if (booksNode != null && booksNode.isArray()) {
                List<FQSearchResponse.BookItem> books = new ArrayList<>();
                for (JsonNode bookNode : booksNode) {
                    books.add(parseBookItem(bookNode));
                }
                searchResponse.setBooks(books);

                if (searchResponse.getTotal() == null) {
                    int total = dataNode != null ? dataNode.path("total").asInt(books.size()) : books.size();
                    searchResponse.setTotal(total);
                }
                if (searchResponse.getHasMore() == null) {
                    Boolean hasMore = null;
                    if (dataNode != null) {
                        hasMore = boolFromNode(dataNode.path("has_more"));
                        if (hasMore == null) {
                            hasMore = boolFromNode(dataNode.path("hasMore"));
                        }
                    }
                    searchResponse.setHasMore(Boolean.TRUE.equals(hasMore));
                }
            }
        }

        if (Texts.isBlank(searchResponse.getSearchId())) {
            String fallback = Texts.firstNonBlank(
                dataNode != null ? dataNode.path("search_id").asText("") : "",
                dataNode != null ? dataNode.path("searchId").asText("") : "",
                jsonResponse != null ? jsonResponse.path("search_id").asText("") : "",
                jsonResponse != null ? jsonResponse.path("searchId").asText("") : ""
            );
            searchResponse.setSearchId(fallback);
        }
        return searchResponse;
    }

    private static FQSearchResponse.BookItem parseBookItem(JsonNode bookNode) {
        FQSearchResponse.BookItem book = new FQSearchResponse.BookItem();
        if (bookNode == null || bookNode.isMissingNode() || bookNode.isNull()) {
            return book;
        }

        book.setBookId(bookNode.path("book_id").asText(""));
        book.setBookName(bookNode.path("book_name").asText(""));
        book.setAuthor(bookNode.path("author").asText(""));
        book.setDescription(Texts.firstNonBlank(
            bookNode.path("abstract").asText(""),
            bookNode.path("book_abstract_v2").asText("")
        ));
        book.setCoverUrl(Texts.firstNonBlank(
            bookNode.path("thumb_url").asText(""),
            bookNode.path("detail_page_thumb_url").asText("")
        ));
        book.setLastChapterTitle(bookNode.path("last_chapter_title").asText(""));
        book.setCategory(bookNode.path("category").asText(""));
        book.setWordCount(bookNode.path("word_number").asLong(0L));

        return book;
    }

    private static Boolean boolFromNode(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        String s = node.asText("").trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
            return true;
        }
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
            return false;
        }
        return null;
    }
}
