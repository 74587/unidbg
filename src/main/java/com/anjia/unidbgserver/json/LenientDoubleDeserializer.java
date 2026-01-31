package com.anjia.unidbgserver.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class LenientDoubleDeserializer extends JsonDeserializer<Double> {
    @Override
    public Double deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_NUMBER_INT || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return p.getDoubleValue();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return 1.0;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return 0.0;
        }
        if (token == JsonToken.VALUE_STRING) {
            String raw = p.getValueAsString();
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
                return null;
            }
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
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
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1.0 : 0.0;
        }
        if (node.isTextual()) {
            String s = node.asText("").trim();
            if (s.isEmpty() || "null".equalsIgnoreCase(s)) return null;
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
