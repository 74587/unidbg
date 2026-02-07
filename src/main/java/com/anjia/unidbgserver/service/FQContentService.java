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
        
        try {
            String key = registerKeyService.getDecryptionKey(keyVersion);
            return FqCrypto.decryptAndDecompressContent(itemContent.getContent(), key);
        } catch (Exception e) {
            log.error("解密内容失败 - keyVersion: {}, 错误: {}", keyVersion, e.getMessage());
            
            // 如果解密失败，尝试使用当前最新的 registerkey
            log.info("尝试使用当前最新的 registerkey 重新解密...");
            try {
                String currentKey = registerKeyService.getDecryptionKey(null);
                String result = FqCrypto.decryptAndDecompressContent(itemContent.getContent(), currentKey);
                log.info("使用当前 registerkey 解密成功");
                return result;
            } catch (Exception retryException) {
                log.error("使用当前 registerkey 重试解密也失败", retryException);
                throw e; // 抛出原始异常
            }
        }
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
