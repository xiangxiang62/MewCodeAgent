package dev.mewcode.agent.llm;

public final class ChatMessage {
    private final Role role;
    private final String content;
    private final java.util.List<ToolCall> toolCalls;
    private final java.util.List<ToolResult> toolResults;

    /**
     * 创建一条对话消息。
     */
    public ChatMessage(Role role, String content) {
        this(role, content, java.util.Collections.emptyList(), java.util.Collections.emptyList());
    }

    /**
     * 创建一条可能携带工具调用或工具结果的对话消息。
     */
    public ChatMessage(Role role, String content,
                       java.util.List<ToolCall> toolCalls,
                       java.util.List<ToolResult> toolResults) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.toolCalls = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(toolCalls));
        this.toolResults = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(toolResults));
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

    public java.util.List<ToolCall> toolCalls() {
        return toolCalls;
    }

    public java.util.List<ToolResult> toolResults() {
        return toolResults;
    }
}
