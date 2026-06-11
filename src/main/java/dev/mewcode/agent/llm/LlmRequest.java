package dev.mewcode.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次模型请求所需的全部结构化输入。
 */
public final class LlmRequest {
    private final List<ChatMessage> messages;
    private final List<ToolDefinition> tools;
    private final String systemPrompt;
    private final String environmentInfo;
    private final String reminder;

    /**
     * 构造一份不可变请求快照，避免后续会话列表继续变化影响本轮请求。
     */
    public LlmRequest(List<ChatMessage> messages,
            List<ToolDefinition> tools,
            String systemPrompt,
            String environmentInfo,
            String reminder) {
        this.messages = Collections.unmodifiableList(new ArrayList<ChatMessage>(messages));
        this.tools = Collections.unmodifiableList(new ArrayList<ToolDefinition>(tools));
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.environmentInfo = environmentInfo == null ? "" : environmentInfo;
        this.reminder = reminder == null ? "" : reminder;
    }

    /**
     * 返回会话消息列表。
     */
    public List<ChatMessage> messages() {
        return messages;
    }

    /**
     * 返回本轮允许调用的工具定义。
     */
    public List<ToolDefinition> tools() {
        return tools;
    }

    /**
     * 返回稳定系统提示块。
     */
    public String systemPrompt() {
        return systemPrompt;
    }

    /**
     * 返回运行时环境信息块。
     */
    public String environmentInfo() {
        return environmentInfo;
    }

    /**
     * 返回本轮临时 reminder 内容。
     */
    public String reminder() {
        return reminder;
    }
}
