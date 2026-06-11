package dev.mewcode.agent.permission;

import java.util.Locale;

/**
 * 定义权限系统的四种运行模式。
 */
public enum Mode {
    DEFAULT("default"),
    ACCEPT_EDITS("acceptEdits"),
    PLAN("plan"),
    BYPASS("bypassPermissions");

    private final String displayName;

    Mode(String displayName) {
        this.displayName = displayName;
    }

    /**
     * 返回配置文件和界面中展示的模式名。
     */
    public String displayName() {
        return displayName;
    }

    /**
     * 解析模式字符串，大小写不敏感。
     */
    public static Mode parse(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("default".equals(normalized)) {
            return DEFAULT;
        }
        if ("acceptedits".equals(normalized)) {
            return ACCEPT_EDITS;
        }
        if ("plan".equals(normalized)) {
            return PLAN;
        }
        if ("bypasspermissions".equals(normalized)) {
            return BYPASS;
        }
        return null;
    }

    /**
     * 返回下一个循环模式，供 Shift+Tab 切换使用。
     */
    public Mode next() {
        Mode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
