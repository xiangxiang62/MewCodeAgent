package dev.mewcode.agent.compact;

import dev.mewcode.agent.compact.state.ContentReplacementState;
import dev.mewcode.agent.compact.state.ContentReplacementState.Decision;
import dev.mewcode.agent.compact.state.ContentReplacementState.DecisionResult;
import dev.mewcode.agent.compact.state.SessionContext;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 实现工具结果的第一层预防性压缩。
 */
public final class Layer1 {
    private Layer1() {
    }

    /**
     * 对会话中的工具结果执行落盘与预览替换。
     */
    public static List<ChatMessage> offloadAndSnip(
            List<ChatMessage> messages,
            ContentReplacementState state,
            SessionContext session) {
        List<ChatMessage> out = new ArrayList<ChatMessage>();
        if (messages == null) {
            return out;
        }
        for (ChatMessage message : messages) {
            if (message.role() != Role.TOOL || message.toolResults().isEmpty()) {
                out.add(new ChatMessage(message.role(), message.content(), message.toolCalls(), message.toolResults()));
                continue;
            }
            List<Candidate> candidates = new ArrayList<Candidate>();
            List<ToolResult> copiedResults = new ArrayList<ToolResult>();
            int aggregateBytes = 0;
            for (ToolResult result : message.toolResults()) {
                ToolResult copied = new ToolResult(result.toolCallId(), result.content(), result.isError());
                copiedResults.add(copied);
                int bytes = utf8Length(result.content());
                aggregateBytes += bytes;
                candidates.add(new Candidate(copiedResults.size() - 1, result, bytes));
            }
            candidates.sort(Comparator.comparingInt(Candidate::bytes).reversed());
            for (Candidate candidate : candidates) {
                boolean shouldReplace = candidate.bytes() > CompactConstants.SINGLE_RESULT_LIMIT
                        || aggregateBytes > CompactConstants.MESSAGE_AGGREGATE_LIMIT;
                if (!shouldReplace) {
                    state.decideOnce(candidate.result().toolCallId(), candidate.result().content(),
                            () -> new DecisionResult(Decision.KEPT, null));
                    continue;
                }
                ToolResult original = copiedResults.get(candidate.index());
                String replaced = state.decideOnce(original.toolCallId(), original.content(), () -> {
                    try {
                        spillSingle(session, original.toolCallId(), original.content());
                    } catch (IOException e) {
                        return new DecisionResult(Decision.SKIP, null);
                    }
                    Path spillPath = session.spillDir().resolve(original.toolCallId());
                    return new DecisionResult(Decision.REPLACED,
                            buildPreview(utf8Length(original.content()), headPreview(original.content()), spillPath));
                });
                if (!replaced.equals(original.content())) {
                    copiedResults.set(candidate.index(), new ToolResult(
                            original.toolCallId(),
                            replaced,
                            original.isError()));
                    aggregateBytes -= candidate.bytes();
                    aggregateBytes += utf8Length(replaced);
                }
            }
            out.add(new ChatMessage(message.role(), message.content(), message.toolCalls(), copiedResults));
        }
        return out;
    }

    /**
     * 将完整工具结果按 id 落盘；同一 id 已存在时直接跳过。
     */
    static void spillSingle(SessionContext session, String toolUseId, String content) throws IOException {
        Path path = session.spillDir().resolve(toolUseId);
        if (Files.exists(path)) {
            return;
        }
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    }

    /**
     * 生成头部预览文本。
     */
    static String headPreview(String content) {
        String[] pieces = (content == null ? "" : content).split("\\R", CompactConstants.PREVIEW_HEAD_LINES + 1);
        int lineCount = Math.min(pieces.length, CompactConstants.PREVIEW_HEAD_LINES);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lineCount; i++) {
            if (i > 0) {
                builder.append(System.lineSeparator());
            }
            builder.append(pieces[i]);
        }
        String head = builder.toString();
        byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= CompactConstants.PREVIEW_HEAD_BYTES) {
            return head;
        }
        return new String(bytes, 0, CompactConstants.PREVIEW_HEAD_BYTES, StandardCharsets.UTF_8);
    }

    /**
     * 构造稳定的预览替换文本。
     */
    static String buildPreview(int originalBytes, String head, Path spillPath) {
        StringBuilder builder = new StringBuilder();
        builder.append("[content offloaded] original size: ")
                .append(originalBytes)
                .append(" bytes")
                .append(System.lineSeparator());
        builder.append("[saved to] ")
                .append(spillPath)
                .append(System.lineSeparator());
        builder.append("[head preview]").append(System.lineSeparator());
        builder.append(head == null ? "" : head).append(System.lineSeparator());
        builder.append("完整内容已保存到上述路径，如需查看请用文件读取工具重新读取该路径，不要仅凭头部预览猜测全貌。");
        return builder.toString();
    }

    /**
     * 计算文本的 UTF-8 字节长度。
     */
    private static int utf8Length(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 表示本轮待评估的工具结果候选项。
     */
    private record Candidate(int index, ToolResult result, int bytes) {
    }
}
