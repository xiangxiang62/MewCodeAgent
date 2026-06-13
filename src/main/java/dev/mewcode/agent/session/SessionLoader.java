package dev.mewcode.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 负责从 conversation.jsonl 恢复消息列表。
 */
public final class SessionLoader {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SessionLoader() {
    }

    /**
     * 从指定 session 目录恢复消息列表。
     */
    public static List<ChatMessage> load(Path sessionDir) throws IOException {
        Path conversation = sessionDir.resolve("conversation.jsonl");
        List<Entry> entries = new ArrayList<Entry>();
        int lastCompactIndex = -1;
        try (BufferedReader reader = Files.newBufferedReader(conversation, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    Entry entry = JSON.readValue(line, Entry.class);
                    entries.add(entry);
                    if ("compact".equals(entry.type())) {
                        lastCompactIndex = entries.size() - 1;
                    }
                } catch (Exception ignored) {
                    // 坏行跳过。
                }
            }
        }

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        for (int i = lastCompactIndex + 1; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.role() == null) {
                continue;
            }
            Role role = toRole(entry.role());
            if (role == null) {
                continue;
            }
            messages.add(new ChatMessage(
                    role,
                    entry.content(),
                    toToolCalls(entry.toolCalls()),
                    toToolResults(entry.toolResults())));
        }
        return truncateOrphanedToolCalls(messages);
    }

    /**
     * 截断末尾孤立的 assistant tool_calls 消息。
     */
    public static List<ChatMessage> truncateOrphanedToolCalls(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (last.role() == Role.ASSISTANT && !last.toolCalls().isEmpty()) {
            return new ArrayList<ChatMessage>(messages.subList(0, messages.size() - 1));
        }
        return messages;
    }

    /**
     * 将字符串角色转成内部 Role 枚举。
     */
    private static Role toRole(String role) {
        for (Role value : Role.values()) {
            if (value.wireName().equals(role)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 将持久化工具调用恢复成运行时对象。
     */
    private static List<ToolCall> toToolCalls(List<Entry.EntryToolCall> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (Entry.EntryToolCall entry : entries) {
            calls.add(new ToolCall(entry.id(), entry.name(), entry.inputJson()));
        }
        return calls;
    }

    /**
     * 将持久化工具结果恢复成运行时对象。
     */
    private static List<ToolResult> toToolResults(List<Entry.EntryToolResult> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ToolResult> results = new ArrayList<ToolResult>();
        for (Entry.EntryToolResult entry : entries) {
            results.add(new ToolResult(entry.toolCallId(), entry.content(), entry.error()));
        }
        return results;
    }
}
