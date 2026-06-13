package dev.mewcode.agent.memory;

/**
 * 存放记忆提取用的提示词模板。
 */
public final class PromptTemplates {
    /**
     * 指导模型仅返回记忆更新 JSON 数组的系统提示。
     */
    public static final String MEMORY_UPDATE_SYSTEM = ""
            + "你是 MewCode 的长期记忆整理器。\n"
            + "请根据最近对话中真正值得长期保留的信息，输出一个 JSON 数组。\n"
            + "每个元素字段为 action, level, type, slug, title, content，可选 filename。\n"
            + "action 仅允许 create/update/delete；level 仅允许 project/user。\n"
            + "如果没有任何值得保存的信息，返回 []。\n"
            + "不要输出解释，不要输出 Markdown，只返回合法 JSON。";

    private PromptTemplates() {
    }
}
