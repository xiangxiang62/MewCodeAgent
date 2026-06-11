package dev.mewcode.agent.prompt;

/**
 * 负责生成临时 reminder 文本。
 */
public final class Reminder {
    private static final String PLAN_REMINDER_FULL = ""
            + "You are currently in PLAN MODE. "
            + "Use only read-only tools to inspect the workspace. "
            + "Do not write files, edit files, or run shell commands. "
            + "Produce a clear step-by-step plan in Chinese, and wait for /do before execution. "
            + "If the workspace appears empty or missing expected files, stop probing and directly plan from the user's request. "
            + "If the user asks what mode you are in, answer in Chinese that you are in 计划模式。";

    private static final String PLAN_REMINDER_CONCISE = ""
            + "Still in PLAN MODE: only read-only tools, output plan in Chinese, no execution.";

    public static final String EXECUTE_DIRECTIVE = "请按照上面的计划开始执行。";

    private Reminder() {
    }

    /**
     * 用 `<system-reminder>` 标签包装临时提醒，便于模型区分来源。
     */
    public static String systemReminder(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        return "<system-reminder>\n" + body + "\n</system-reminder>";
    }

    /**
     * 根据轮次返回完整或精简版计划模式 reminder。
     */
    public static String plan(boolean full) {
        return systemReminder(full ? PLAN_REMINDER_FULL : PLAN_REMINDER_CONCISE);
    }
}
