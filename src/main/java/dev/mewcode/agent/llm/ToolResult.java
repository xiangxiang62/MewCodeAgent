package dev.mewcode.agent.llm;

/**
 * 表示一次工具调用执行后的结构化结果。
 */
public final class ToolResult {
    private final String toolCallId;
    private final String content;
    private final boolean error;

    /**
     * 创建一条工具结果；错误也以普通文本形式回传给模型。
     */
    public ToolResult(String toolCallId, String content, boolean error) {
        this.toolCallId = toolCallId;
        this.content = content == null ? "" : content;
        this.error = error;
    }

    /**
     * 返回对应的工具调用 ID。
     */
    public String toolCallId() {
        return toolCallId;
    }

    /**
     * 返回结果文本。
     */
    public String content() {
        return content;
    }

    /**
     * 标记结果是否为错误。
     */
    public boolean isError() {
        return error;
    }
}
