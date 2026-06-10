package dev.mewcode.agent.llm;

public final class ToolCall {
    private final String id;
    private final String name;
    private final String inputJson;

    /**
     * 模型发起的一次工具调用，保留 provider 侧 id 以便结果回灌时配对。
     */
    public ToolCall(String id, String name, String inputJson) {
        this.id = id;
        this.name = name;
        this.inputJson = inputJson == null || inputJson.trim().isEmpty() ? "{}" : inputJson;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String inputJson() {
        return inputJson;
    }
}
