package dev.mewcode.agent.llm;

/**
 * 表示模型发起的一次工具调用。
 */
public final class ToolCall {
    private final String id;
    private final String name;
    private final String inputJson;

    /**
     * 创建一条工具调用记录；空参数会被归一化为 `{}`。
     */
    public ToolCall(String id, String name, String inputJson) {
        this.id = id;
        this.name = name;
        this.inputJson = inputJson == null || inputJson.trim().isEmpty() ? "{}" : inputJson;
    }

    /**
     * 返回工具调用唯一标识。
     */
    public String id() {
        return id;
    }

    /**
     * 返回目标工具名。
     */
    public String name() {
        return name;
    }

    /**
     * 返回工具参数 JSON。
     */
    public String inputJson() {
        return inputJson;
    }
}
