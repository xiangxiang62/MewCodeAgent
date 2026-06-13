package dev.mewcode.agent.memory;

/**
 * 定义长期记忆支持的笔记类型。
 */
public enum NoteType {
    PROJECT_FACT("project_fact"),
    USER_PREFERENCE("user_preference"),
    WORKFLOW("workflow"),
    REMINDER("reminder");

    private final String wire;

    NoteType(String wire) {
        this.wire = wire;
    }

    /**
     * 返回写入磁盘和提示词时使用的 snake_case 名称。
     */
    public String wire() {
        return wire;
    }

    /**
     * 从外部字符串恢复枚举值，无法匹配时返回 null。
     */
    public static NoteType fromWire(String wire) {
        if (wire == null) {
            return null;
        }
        for (NoteType value : values()) {
            if (value.wire.equalsIgnoreCase(wire.trim())) {
                return value;
            }
        }
        return null;
    }
}
