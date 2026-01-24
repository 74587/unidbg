package com.anjia.unidbgserver.utils;

import org.springframework.http.ResponseEntity;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * GZIP 压缩/解压缩工具类
 * 统一处理上游响应的 GZIP 解压逻辑
 */
public final class GzipUtils {

    private GzipUtils() {}

    /**
     * 解压缩 GZIP 响应体（基础方法）
     *
     * @param gzipData 可能是 GZIP 压缩的字节数组
     * @return 解压后的字符串
     * @throws Exception 解压失败时抛出异常
     */
    public static String decompressGzipResponse(byte[] gzipData) throws Exception {
        if (gzipData == null || gzipData.length == 0) {
            return "";
        }

        // Some upstream responses are not gzip (or already decompressed).
        boolean looksLikeGzip = gzipData.length >= 2
            && (gzipData[0] == (byte) 0x1f)
            && (gzipData[1] == (byte) 0x8b);
        if (!looksLikeGzip) {
            return new String(gzipData, StandardCharsets.UTF_8);
        }

        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(gzipData))) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = gzipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, length);
            }
            return new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.util.zip.ZipException e) {
            // Fallback: not gzip (or already decompressed).
            return new String(gzipData, StandardCharsets.UTF_8);
        }
    }

    /**
     * 统一解码上游响应（自动处理 GZIP 压缩）
     * 根据 Content-Encoding 头部和魔数自动判断是否需要解压
     *
     * @param response HTTP 响应
     * @return 解码后的字符串
     */
    public static String decodeUpstreamResponse(ResponseEntity<byte[]> response) {
        if (response == null) {
            return "";
        }
        byte[] body = response.getBody();
        if (body == null || body.length == 0) {
            return "";
        }

        boolean isGzip = false;
        List<String> enc = response.getHeaders() != null ? response.getHeaders().get("Content-Encoding") : null;
        if (enc != null) {
            for (String e : enc) {
                if (e != null && e.toLowerCase(Locale.ROOT).contains("gzip")) {
                    isGzip = true;
                    break;
                }
            }
        }
        if (!isGzip && body.length >= 2 && body[0] == (byte) 0x1f && body[1] == (byte) 0x8b) {
            isGzip = true;
        }

        if (!isGzip) {
            return new String(body, StandardCharsets.UTF_8);
        }

        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(body))) {
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int length;
            while ((length = gzipInputStream.read(buffer)) != -1) {
                byteArrayOutputStream.write(buffer, 0, length);
            }
            return new String(byteArrayOutputStream.toByteArray(), StandardCharsets.UTF_8);
        } catch (java.util.zip.ZipException e) {
            // 上游偶尔会返回非 gzip 内容但误标为 gzip，兜底为原始文本
            return new String(body, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}

