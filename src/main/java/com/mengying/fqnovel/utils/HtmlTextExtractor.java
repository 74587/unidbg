package com.mengying.fqnovel.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML 文本提取工具类
 * 用于从番茄小说的 HTML 内容中提取纯文本
 */
public class HtmlTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(HtmlTextExtractor.class);

    // 预编译正则表达式，提高性能
    private static final Pattern BLK_PATTERN = Pattern.compile("<blk[^>]*>([^<]*)</blk>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_PATTERN = Pattern.compile("<h1[^>]*>.*?<blk[^>]*>([^<]*)</blk>.*?</h1>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private HtmlTextExtractor() {
        // 工具类，禁止实例化
    }

    /**
     * 从 HTML 内容中提取纯文本
     * 主要提取 <blk> 标签中的文本内容
     *
     * @param htmlContent HTML 内容
     * @return 提取的纯文本内容
     */
    public static String extractText(String htmlContent) {
        if (!Texts.hasText(htmlContent)) {
            return "";
        }

        StringBuilder textBuilder = new StringBuilder();

        try {
            Matcher matcher = BLK_PATTERN.matcher(htmlContent);

            while (matcher.find()) {
                String text = matcher.group(1);
                if (Texts.hasText(text)) {
                    textBuilder.append(Texts.trimToEmpty(text)).append("\n");
                }
            }

            // 如果没有找到 <blk> 标签，尝试提取所有文本内容
            if (textBuilder.length() == 0) {
                String text = Texts.trimToNull(htmlContent.replaceAll("<[^>]+>", ""));
                if (text != null) {
                    textBuilder.append(text);
                }
            }

        } catch (Exception e) {
            log.warn("HTML 文本提取失败，返回去除标签的简单文本", e);
            return Texts.trimToEmpty(htmlContent.replaceAll("<[^>]+>", ""));
        }

        return Texts.trimToEmpty(textBuilder.toString());
    }

    /**
     * 从 HTML 内容中提取标题
     * 从 <h1><blk>...</blk></h1> 结构中提取
     *
     * @param htmlContent HTML 内容
     * @return 提取的标题，如果未找到返回 null
     */
    public static String extractTitle(String htmlContent) {
        if (!Texts.hasText(htmlContent)) {
            return null;
        }

        try {
            Matcher titleMatcher = TITLE_PATTERN.matcher(htmlContent);
            if (titleMatcher.find()) {
                return Texts.trimToEmpty(titleMatcher.group(1));
            }
        } catch (Exception e) {
            log.debug("标题提取失败", e);
        }

        return null;
    }
}
