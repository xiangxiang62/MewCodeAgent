package dev.mewcode.agent.llm;

public final class ChatMessage {
    private final Role role;
    private final String content;

    /**
     * 创建一条对话消息。
     */
    public ChatMessage(Role role, String content) {
        this.role = role;
        this.content = content;
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
}
