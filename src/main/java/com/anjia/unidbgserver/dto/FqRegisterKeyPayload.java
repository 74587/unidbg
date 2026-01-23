package com.anjia.unidbgserver.dto;

import com.anjia.unidbgserver.service.FqCrypto;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * FQNovel 注册密钥载荷
 * 对应 Rust 中的 FqRegisterKeyPayload 结构
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FqRegisterKeyPayload {

    /**
     * 密钥内容 (加密后的)
     */
    @JsonProperty("content")
    private String content;

    /**
     * 密钥版本
     */
    @JsonProperty("keyver")
    private long keyver;

    /**
     * 根据变量创建注册密钥载荷
     *
     * @param var FQ变量
     */
    public FqRegisterKeyPayload(FqVariable var) throws Exception {
        FqCrypto crypto = new FqCrypto(FqCrypto.REG_KEY);
        this.content = crypto.newRegisterKeyContent(var.getServerDeviceId(), "0");
        this.keyver = 1;
    }
}
