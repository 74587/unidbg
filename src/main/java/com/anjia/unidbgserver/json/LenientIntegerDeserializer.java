package com.anjia.unidbgserver.json;

import com.anjia.unidbgserver.utils.Texts;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;

public class LenientIntegerDeserializer extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getIntValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return (int) p.getDoubleValue();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return 1;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return 0;
        }
        if (token == JsonToken.VALUE_STRING) {
            String s = Texts.trimToNull(p.getValueAsString());
            if (s == null || "null".equalsIgnoreCase(s)) {
                return null;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                try {
                    return (int) Double.parseDouble(s);
                } catch (NumberFormatException ignored2) {
                    return null;
                }
            }
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isTextual()) {
            String s = Texts.trimToNull(node.asText(""));
            if (s == null || "null".equalsIgnoreCase(s)) return null;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1 : 0;
        }
        return null;
    }
}
