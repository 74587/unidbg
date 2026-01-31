package com.anjia.unidbgserver.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class LenientLongDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getLongValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return (long) p.getDoubleValue();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return 1L;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return 0L;
        }
        if (token == JsonToken.VALUE_STRING) {
            String raw = p.getValueAsString();
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return null;
            }
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                try {
                    return (long) Double.parseDouble(s);
                } catch (NumberFormatException ignored2) {
                    return null;
                }
            }
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1L : 0L;
        }
        if (node.isTextual()) {
            String s = node.asText("").trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
