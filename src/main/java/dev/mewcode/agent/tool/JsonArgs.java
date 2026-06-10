package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

final class JsonArgs {
    static final ObjectMapper JSON = new ObjectMapper();

    private JsonArgs() {
    }

    static JsonNode parse(String inputJson) throws IOException {
        String normalized = inputJson == null || inputJson.trim().isEmpty() ? "{}" : inputJson;
        return JSON.readTree(normalized);
    }

    static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return value.asText();
    }

    static String optionalText(JsonNode node, String name, String defaultValue) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isEmpty()) {
            return defaultValue;
        }
        return value.asText();
    }
}
