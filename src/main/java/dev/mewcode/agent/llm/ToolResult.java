package dev.mewcode.agent.llm;

public final class ToolResult {
    private final String toolCallId;
    private final String content;
    private final boolean error;

    /**
     * 一次工具执行结果。错误也以结构化文本回灌，避免打断会话。
     */
    public ToolResult(String toolCallId, String content, boolean error) {
        this.toolCallId = toolCallId;
        this.content = content == null ? "" : content;
        this.error = error;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String content() {
        return content;
    }

    public boolean isError() {
        return error;
    }
}
