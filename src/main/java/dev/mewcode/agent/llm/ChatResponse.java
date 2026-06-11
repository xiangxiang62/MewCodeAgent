package dev.mewcode.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一轮模型返回的完整结果，包含文本、工具调用和 token 用量。
 */
public final class ChatResponse {
    private final String text;
    private final List<ToolCall> toolCalls;
    private final Usage usage;

    /**
     * 创建不含用量信息的响应。
     */
    public ChatResponse(String text, List<ToolCall> toolCalls) {
        this(text, toolCalls, Usage.zero());
    }

    /**
     * 创建完整响应对象。
     */
    public ChatResponse(String text, List<ToolCall> toolCalls, Usage usage) {
        this.text = text == null ? "" : text;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.usage = usage == null ? Usage.zero() : usage;
    }

    /**
     * 返回模型最终聚合出的文本。
     */
    public String text() {
        return text;
    }

    /**
     * 返回模型要求执行的工具调用列表。
     */
    public List<ToolCall> toolCalls() {
        return toolCalls;
    }

    /**
     * 返回本轮请求的 token 用量。
     */
    public Usage usage() {
        return usage;
    }
}
