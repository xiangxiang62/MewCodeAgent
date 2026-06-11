package dev.mewcode.agent.permission;

/**
 * 表示工具调用所属的权限类别。
 */
public enum Category {
    READ("读取"),
    WRITE("写入"),
    EXEC("命令执行");

    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 返回中文类别名。
     */
    public String displayName() {
        return displayName;
    }
}
