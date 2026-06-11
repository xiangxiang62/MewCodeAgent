package dev.mewcode.agent.prompt;

/**
 * 系统提示中的单个模块单元。
 */
public final class PromptModule {
    private final String name;
    private final int priority;
    private final String content;

    /**
     * 创建一个提示模块。
     */
    public PromptModule(String name, int priority, String content) {
        this.name = name;
        this.priority = priority;
        this.content = content == null ? "" : content;
    }

    /**
     * 返回模块名称。
     */
    public String name() {
        return name;
    }

    /**
     * 返回模块优先级，值越小越靠前。
     */
    public int priority() {
        return priority;
    }

    /**
     * 返回模块内容。
     */
    public String content() {
        return content;
    }
}
