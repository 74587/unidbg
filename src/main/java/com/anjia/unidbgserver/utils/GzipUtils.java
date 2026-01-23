package com.anjia.unidbgserver.utils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * Small helper for upstream responses that are usually gzip-compressed but may be plain bytes.
 */
public final class GzipUtils {

    private GzipUtils() {}

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
}

