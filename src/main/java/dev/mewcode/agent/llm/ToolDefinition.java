package dev.mewcode.agent.llm;

import java.util.Map;

public final class ToolDefinition {
    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;

    /**
     * 协议无关的工具定义，由 provider 适配为各自 API 的 tools 字段。
     */
    public ToolDefinition(String name, String description, Map<String, Object> inputSchema) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> inputSchema() {
        return inputSchema;
    }
}
