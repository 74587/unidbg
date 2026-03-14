package com.mengying.fqnovel.dto;

/**
 * 对外搜索请求 DTO。
 * 仅保留控制器和缓存键真正需要的字段。
 */
public class FQSearchRequest {

    private String query;
    private Integer offset;
    private Integer count;
    private Integer passback;
    private Integer tabType;
    private String searchId;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public Integer getOffset() {
        return offset;
    }

    public void setOffset(Integer offset) {
        this.offset = offset;
    }

    public Integer getCount() {
        return count;
    }

    public void setCount(Integer count) {
        this.count = count;
    }

    public Integer getPassback() {
        return passback;
    }

    public void setPassback(Integer passback) {
        this.passback = passback;
    }

    public Integer getTabType() {
        return tabType;
    }

    public void setTabType(Integer tabType) {
        this.tabType = tabType;
    }

    public String getSearchId() {
        return searchId;
    }

    public void setSearchId(String searchId) {
        this.searchId = searchId;
    }
}
