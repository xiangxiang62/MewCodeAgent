package dev.mewcode.agent.llm;

/**
 * 统一表示一条会话消息，可携带普通文本、工具调用或工具结果。
 */
public final class ChatMessage {
    private final Role role;
    private final String content;
    private final java.util.List<ToolCall> toolCalls;
    private final java.util.List<ToolResult> toolResults;

    /**
     * 创建一条普通对话消息。
     */
    public ChatMessage(Role role, String content) {
        this(role, content, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    /**
     * 创建一条可携带工具调用或工具结果的消息。
     */
    public ChatMessage(Role role, String content,
            java.util.List<ToolCall> toolCalls,
            java.util.List<ToolResult> toolResults) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCalls = java.util.Collections.unmodifiableList(new java.util.ArrayList<ToolCall>(toolCalls));
        this.toolResults = java.util.Collections.unmodifiableList(new java.util.ArrayList<ToolResult>(toolResults));
    }

    /**
     * 返回消息角色。
     */
    public Role role() {
        return role;
    }

    /**
     * 返回消息文本内容。
     */
    public String content() {
        return content;
    }

    /**
     * 返回消息中携带的工具调用列表。
     */
    public java.util.List<ToolCall> toolCalls() {
        return toolCalls;
    }

    /**
     * 返回消息中携带的工具结果列表。
     */
    public java.util.List<ToolResult> toolResults() {
        return toolResults;
    }
}
