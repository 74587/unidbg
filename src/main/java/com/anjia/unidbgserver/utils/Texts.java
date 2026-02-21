package com.anjia.unidbgserver.utils;

/**
 * 轻量字符串工具，统一空白判断和回退取值。
 */
public final class Texts {

    private Texts() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static boolean hasText(String value) {
        return !isBlank(value);
    }

    public static String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (hasText(value)) {
                return value;
            }
        }
        return "";
    }
}
