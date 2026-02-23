package com.anjia.unidbgserver.dto;

/**
 * FQNovel API 通用响应。
 */
public record FQNovelResponse<T>(
    Integer code,
    String message,
    T data,
    Long serverTime
) {

    public static final int SUCCESS_CODE = 0;
    public static final int DEFAULT_ERROR_CODE = -1;
    public static final String SUCCESS_MESSAGE = "success";

    public static <T> FQNovelResponse<T> success(T data) {
        return new FQNovelResponse<>(SUCCESS_CODE, SUCCESS_MESSAGE, data, System.currentTimeMillis());
    }

    public static <T> FQNovelResponse<T> error(String message) {
        return new FQNovelResponse<>(DEFAULT_ERROR_CODE, message, null, System.currentTimeMillis());
    }

    public static <T> FQNovelResponse<T> error(Integer code, String message) {
        return new FQNovelResponse<>(code, message, null, System.currentTimeMillis());
    }

    public boolean isSuccess() {
        return code != null && code == SUCCESS_CODE;
    }

    // 兼容现有调用风格（record 原生访问器为 code()/message()/data()/serverTime()）
    public Integer getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }

    public Long getServerTime() {
        return serverTime;
    }
}
