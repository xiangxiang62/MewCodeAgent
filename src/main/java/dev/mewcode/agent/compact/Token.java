package dev.mewcode.agent.compact;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.llm.Usage;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 提供轻量 token 估算能力。
 */
public final class Token {
    private Token() {
    }

    /**
     * 将 provider 返回的 usage 聚合成新的锚点值。
     */
    public static long usageAnchor(Usage usage) {
        if (usage == null) {
            return 0L;
        }
        return usage.inputTokens()
                + usage.outputTokens()
                + usage.cacheReadTokens()
                + usage.cacheWriteTokens();
    }

    /**
     * 基于 usage 锚点和新增消息内容估算当前总 token。
     */
    public static long estimateTokens(long anchor, List<ChatMessage> allMessages, int anchorMessageCount) {
        int safeStart = Math.min(Math.max(anchorMessageCount, 0), allMessages == null ? 0 : allMessages.size());
        List<ChatMessage> tail = allMessages == null ? java.util.Collections.<ChatMessage>emptyList()
                : allMessages.subList(safeStart, allMessages.size());
        return anchor + (long) Math.ceil(messageChars(tail) / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
    }

    /**
     * 统计消息正文、工具调用参数和工具结果的 UTF-8 字节总量。
     */
    static int messageChars(List<ChatMessage> messages) {
        int sum = 0;
        if (messages == null) {
            return 0;
        }
        for (ChatMessage message : messages) {
            sum += utf8Length(message == null ? null : message.content());
            if (message == null) {
                continue;
            }
            for (ToolCall call : message.toolCalls()) {
                sum += utf8Length(call == null ? null : call.inputJson());
            }
            for (ToolResult result : message.toolResults()) {
                sum += utf8Length(result == null ? null : result.content());
            }
        }
        return sum;
    }

    /**
     * 返回文本的 UTF-8 字节长度。
     */
    private static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
