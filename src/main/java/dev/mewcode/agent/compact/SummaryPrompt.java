package dev.mewcode.agent.compact;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 构造摘要请求 prompt，并从返回结果中提取正式摘要。
 */
public final class SummaryPrompt {
    private static final String SUMMARY_INSTRUCTION = """
            请先在 <analysis> 标签内写分析草稿，再在 <summary> 标签内输出正式摘要。
            不要调用任何工具，只输出纯文本。
            <summary> 内必须严格包含以下 9 个小节，并按顺序输出：
            1. 主要请求和意图
            2. 关键技术概念
            3. 文件和代码片段
            4. 错误和修复
            5. 问题解决过程
            6. 所有用户消息原文
            7. 待办任务
            8. 当前工作
            9. 可能的下一步
            """;

    private SummaryPrompt() {
    }

    /**
     * 构造摘要请求消息列表。
     */
    public static List<ChatMessage> buildSummaryPrompt(List<ChatMessage> messages) {
        List<ChatMessage> prompt = new ArrayList<ChatMessage>();
        prompt.add(new ChatMessage(Role.USER, SUMMARY_INSTRUCTION + System.lineSeparator()
                + System.lineSeparator()
                + "[conversation]" + System.lineSeparator()
                + serializeConversation(messages)));
        return prompt;
    }

    /**
     * 将当前对话序列化为便于摘要的纯文本。
     */
    static String serializeConversation(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        if (messages == null) {
            return "";
        }
        for (ChatMessage message : messages) {
            builder.append(message.role().wireName()).append(": ").append(message.content()).append(System.lineSeparator());
            for (ToolCall call : message.toolCalls()) {
                builder.append("tool_call: ").append(call.name()).append(" ").append(call.inputJson())
                        .append(System.lineSeparator());
            }
            for (ToolResult result : message.toolResults()) {
                builder.append("tool_result: ").append(result.toolCallId()).append(" ").append(result.content())
                        .append(System.lineSeparator());
            }
        }
        return builder.toString().trim();
    }

    /**
     * 从模型返回内容中提取 <summary> 段。
     */
    public static String extractSummary(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int start = raw.lastIndexOf("<summary>");
        int end = raw.lastIndexOf("</summary>");
        if (start < 0 || end < 0 || end <= start) {
            return raw;
        }
        return raw.substring(start + "<summary>".length(), end).strip();
    }
}
