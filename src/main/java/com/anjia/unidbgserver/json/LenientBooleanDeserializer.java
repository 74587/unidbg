package com.anjia.unidbgserver.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class LenientBooleanDeserializer extends JsonDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_TRUE) {
            return true;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return false;
        }
        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getIntValue() != 0;
        }
        if (token == JsonToken.VALUE_STRING) {
            String raw = p.getValueAsString();
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return null;
            }
            if ("1".equals(s) || "true".equalsIgnoreCase(s)) {
                return true;
            }
            if ("0".equals(s) || "false".equalsIgnoreCase(s)) {
                return false;
            }
            return null;
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.intValue() != 0;
        }
        if (node.isTextual()) {
            String s = node.asText("").trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            if ("1".equals(s) || "true".equalsIgnoreCase(s)) return true;
            if ("0".equals(s) || "false".equalsIgnoreCase(s)) return false;
        }
        return null;
    }
}
