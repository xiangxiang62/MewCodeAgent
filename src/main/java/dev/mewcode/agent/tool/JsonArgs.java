package dev.mewcode.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 统一处理工具参数 JSON 的解析和取值。
 */
final class JsonArgs {
    static final ObjectMapper JSON = new ObjectMapper();

    private JsonArgs() {
    }

    /**
     * 解析工具输入；空输入会被视作空对象。
     */
    static JsonNode parse(String inputJson) throws IOException {
        String normalized = inputJson == null || inputJson.trim().isEmpty() ? "{}" : inputJson;
        return JSON.readTree(normalized);
    }

    /**
     * 读取必填字符串参数；缺失或为空时抛出异常。
     */
    static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException("缺少必填参数: " + name);
        }
        return value.asText();
    }

    /**
     * 读取可选字符串参数；不存在时返回默认值。
     */
    static String optionalText(JsonNode node, String name, String defaultValue) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isEmpty()) {
            return defaultValue;
        }
        return value.asText();
    }
}
