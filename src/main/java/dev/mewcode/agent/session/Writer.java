package dev.mewcode.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolResult;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 负责向 conversation.jsonl 追加写入会话记录。
 */
public final class Writer implements Closeable {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path file;
    private final BufferedWriter out;
    private final ReentrantLock lock = new ReentrantLock();
    private boolean hasWritten;

    private Writer(Path file, BufferedWriter out, boolean hasWritten) {
        this.file = file;
        this.out = out;
        this.hasWritten = hasWritten;
    }

    /**
     * 创建或打开当前会话的 JSONL 文件。
     */
    public static Writer create(Path sessionDir) throws IOException {
        Files.createDirectories(sessionDir);
        return open(sessionDir);
    }

    /**
     * 以追加模式打开当前会话的 JSONL 文件。
     */
    public static Writer open(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve("conversation.jsonl");
        BufferedWriter out = Files.newBufferedWriter(
                file,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE);
        return new Writer(file, out, Files.exists(file) && Files.size(file) > 0L);
    }

    /**
     * 追加一条消息记录。
     */
    public void append(ChatMessage message, String model, boolean first) throws IOException {
        Entry entry = new Entry(
                null,
                message.role().wireName(),
                message.content(),
                message.toolCalls().isEmpty() ? null : toEntryToolCalls(message.toolCalls()),
                message.toolResults().isEmpty() ? null : toEntryToolResults(message.toolResults()),
                System.currentTimeMillis() / 1000L,
                first && !hasWritten ? model : null);
        writeLine(JSON.writeValueAsString(entry));
    }

    /**
     * 追加一个 compact 标记行。
     */
    public void writeCompactMarker() throws IOException {
        Entry entry = new Entry("compact", null, null, null, null, System.currentTimeMillis() / 1000L, null);
        writeLine(JSON.writeValueAsString(entry));
    }

    /**
     * 追加一组消息。
     */
    public void appendAll(List<ChatMessage> messages) throws IOException {
        for (ChatMessage message : messages) {
            append(message, null, false);
        }
    }

    /**
     * 返回当前 JSONL 文件路径。
     */
    public Path file() {
        return file;
    }

    /**
     * 关闭底层 writer。
     */
    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            out.close();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 以加锁方式写入一行并立即 flush。
     */
    private void writeLine(String line) throws IOException {
        lock.lock();
        try {
            out.write(line);
            out.newLine();
            out.flush();
            hasWritten = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 将运行时 ToolCall 转成持久化投影。
     */
    private List<Entry.EntryToolCall> toEntryToolCalls(List<ToolCall> toolCalls) {
        List<Entry.EntryToolCall> entries = new ArrayList<Entry.EntryToolCall>();
        for (ToolCall call : toolCalls) {
            entries.add(new Entry.EntryToolCall(call.id(), call.name(), call.inputJson()));
        }
        return entries;
    }

    /**
     * 将运行时 ToolResult 转成持久化投影。
     */
    private List<Entry.EntryToolResult> toEntryToolResults(List<ToolResult> toolResults) {
        List<Entry.EntryToolResult> entries = new ArrayList<Entry.EntryToolResult>();
        for (ToolResult result : toolResults) {
            entries.add(new Entry.EntryToolResult(result.toolCallId(), result.content(), result.isError()));
        }
        return entries;
    }
}
