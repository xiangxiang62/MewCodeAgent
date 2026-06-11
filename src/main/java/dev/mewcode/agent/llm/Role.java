package dev.mewcode.agent.llm;

/**
 * 会话消息角色。
 */
public enum Role {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool");

    private final String wireName;

    Role(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 返回发送给模型协议时使用的角色名。
     */
    public String wireName() {
        return wireName;
    }
}
