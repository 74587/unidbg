package com.anjia.unidbgserver.dto;

/**
 * FQNovel 注册密钥响应。
 */
public record FqRegisterKeyResponse(
    long code,
    String message,
    FqRegisterKeyPayloadResponse data
) {
    public long getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public FqRegisterKeyPayloadResponse getData() {
        return data;
    }
}
