package dev.mewcode.agent.compact;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.PromptTooLongException;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 实现基于摘要的第二层上下文压缩。
 */
public final class Layer2 {
    private Layer2() {
    }

    /**
     * 执行一次摘要压缩，并返回替换后的消息序列。
     */
    public static List<ChatMessage> summarizeOnce(
            LlmProvider provider,
            List<ChatMessage> messages,
            String systemPrompt,
            String environmentInfo,
            List<ToolDefinition> definitions,
            String recoveryAttachment) throws Exception {
        ChatResponse response = provider.streamChat(
                new LlmRequest(
                        SummaryPrompt.buildSummaryPrompt(messages),
                        Collections.<ToolDefinition>emptyList(),
                        systemPrompt,
                        environmentInfo,
                        ""),
                StreamCallback.noop());
        String summary = SummaryPrompt.extractSummary(response.text());
        List<ChatMessage> compacted = new ArrayList<ChatMessage>();
        compacted.add(new ChatMessage(Role.SYSTEM, "[MEWCODE_CONTEXT_SUMMARY]\n" + summary));
        if (recoveryAttachment != null && !recoveryAttachment.trim().isEmpty()) {
            compacted.add(new ChatMessage(Role.SYSTEM, recoveryAttachment));
        }
        compacted.addAll(pickRecentTail(messages));
        return compacted;
    }

    /**
     * 针对摘要请求自身 PTL 的情况做递进裁剪重试。
     */
    public static List<ChatMessage> summarizeWithRetry(
            LlmProvider provider,
            List<ChatMessage> messages,
            String systemPrompt,
            String environmentInfo,
            List<ToolDefinition> definitions,
            String recoveryAttachment) throws Exception {
        List<ChatMessage> working = new ArrayList<ChatMessage>(messages);
        int directRetries = 0;
        while (!working.isEmpty()) {
            try {
                return summarizeOnce(provider, working, systemPrompt, environmentInfo, definitions, recoveryAttachment);
            } catch (PromptTooLongException e) {
                if (directRetries < CompactConstants.PTL_RETRY_LIMIT) {
                    directRetries++;
                    working = dropOldestGroups(working, 1);
                } else {
                    int groups = Math.max(1,
                            (int) Math.ceil(groupByUserTurn(working).size() * CompactConstants.PTL_DROP_PERCENTAGE));
                    working = dropOldestGroups(working, groups);
                }
            }
        }
        throw new CompactException("摘要请求仍然过长，无法完成压缩");
    }

    /**
     * 从原始消息尾部保留最近的原文上下文。
     */
    static List<ChatMessage> pickRecentTail(List<ChatMessage> messages) {
        List<ChatMessage> tail = new ArrayList<ChatMessage>();
        if (messages == null || messages.isEmpty()) {
            return tail;
        }
        long tokens = 0L;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            tail.add(0, message);
            tokens += (long) Math.ceil(Token.messageChars(Collections.singletonList(message))
                    / CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
            if (tokens >= CompactConstants.RECENT_KEEP_TOKENS && tail.size() >= CompactConstants.RECENT_KEEP_MESSAGES) {
                break;
            }
        }
        return tail;
    }

    /**
     * 以“用户消息 + 后续 assistant/tool”作为一个分组，便于 PTL 时整组裁剪。
     */
    static List<List<ChatMessage>> groupByUserTurn(List<ChatMessage> messages) {
        List<List<ChatMessage>> groups = new ArrayList<List<ChatMessage>>();
        List<ChatMessage> current = new ArrayList<ChatMessage>();
        for (ChatMessage message : messages) {
            if (message.role() == Role.USER && !current.isEmpty()) {
                groups.add(current);
                current = new ArrayList<ChatMessage>();
            }
            current.add(message);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * 丢弃最老的若干组消息。
     */
    static List<ChatMessage> dropOldestGroups(List<ChatMessage> messages, int groupsToDrop) {
        List<List<ChatMessage>> groups = groupByUserTurn(messages);
        if (groups.size() <= groupsToDrop) {
            return Collections.emptyList();
        }
        List<ChatMessage> flattened = new ArrayList<ChatMessage>();
        for (int i = groupsToDrop; i < groups.size(); i++) {
            flattened.addAll(groups.get(i));
        }
        return flattened;
    }
}
