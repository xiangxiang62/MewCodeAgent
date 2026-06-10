package dev.mewcode.agent.llm;

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
     * 返回发送给 LLM API 时使用的角色名称。
     */
    public String wireName() {
        return wireName;
    }
}
