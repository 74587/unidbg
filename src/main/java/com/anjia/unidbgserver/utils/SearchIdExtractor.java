package com.anjia.unidbgserver.utils;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 从任意层级响应中提取 search_id。
 */
public final class SearchIdExtractor {

    private SearchIdExtractor() {
    }

    public static String deepFind(JsonNode root) {
        if (root == null) {
            return "";
        }

        String direct = Texts.firstNonBlank(
            root.path("search_id").asText(""),
            root.path("searchId").asText(""),
            root.path("search_id_str").asText("")
        );
        if (Texts.hasText(direct)) {
            return direct;
        }

        Deque<JsonNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node == null) {
                continue;
            }

            if (node.isObject()) {
                String found = Texts.firstNonBlank(
                    node.path("search_id").asText(""),
                    node.path("searchId").asText(""),
                    node.path("search_id_str").asText("")
                );
                if (Texts.hasText(found)) {
                    return found;
                }
                node.fields().forEachRemaining(e -> stack.push(e.getValue()));
            } else if (node.isArray()) {
                for (JsonNode child : node) {
                    stack.push(child);
                }
            }
        }

        return "";
    }
}
