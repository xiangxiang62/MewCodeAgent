package dev.mewcode.agent.llm;

import java.util.Map;

/**
 * 与具体协议无关的工具定义对象。
 */
public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    /**
     * 创建一份工具定义，后续会由 Provider 转成各自协议格式。
     */
    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    /**
     * 返回工具名。
     */
    public String name() {
        return name;
    }

    /**
     * 返回工具描述。
     */
    public String description() {
        return description;
    }

    /**
     * 返回参数 schema。
     */
    public Map<String, Object> inputSchema() {
        return inputSchema;
    }
}
