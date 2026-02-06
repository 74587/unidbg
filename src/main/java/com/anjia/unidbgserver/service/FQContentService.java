package com.anjia.unidbgserver.service;

import com.anjia.unidbgserver.dto.FqIBatchFullResponse;
import com.anjia.unidbgserver.dto.ItemContent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FQContentService {

    @Resource
    private FQRegisterKeyService registerKeyService;

    public String decryptAndDecompress(ItemContent itemContent) throws Exception {
        if (itemContent == null) {
            throw new IllegalArgumentException("itemContent 不能为空");
        }
        Long keyVersion = itemContent.getKeyVersion();
        String key = registerKeyService.getDecryptionKey(keyVersion);
        return FqCrypto.decryptAndDecompressContent(itemContent.getContent(), key);
    }

    public List<Map.Entry<String, String>> decryptBatchContents(FqIBatchFullResponse response) {
        List<Map.Entry<String, String>> results = new ArrayList<>();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            return results;
        }

        for (Map.Entry<String, ItemContent> entry : response.getData().entrySet()) {
            String itemId = entry.getKey();
            ItemContent content = entry.getValue();

            try {
                String decryptedContent = decryptAndDecompress(content);
                results.add(new java.util.AbstractMap.SimpleEntry<>(itemId, decryptedContent));
            } catch (Exception e) {
                log.error("解密章节内容失败 - itemId: {}, keyVersion: {}", itemId, content != null ? content.getKeyVersion() : null, e);
            }
        }

        return results;
    }
}
