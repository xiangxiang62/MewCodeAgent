package dev.mewcode.agent.session;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolCall;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 session JSONL 的写入、恢复与清理逻辑。
 */
public class SessionTest {
    /**
     * 普通消息应能写回并正确恢复。
     */
    @Test
    public void appendAndRead() throws Exception {
        Path sessionDir = Files.createTempDirectory("mewcode-session");
        Writer writer = Writer.create(sessionDir);
        writer.append(new ChatMessage(Role.USER, "hello"), "fake-model", true);
        writer.append(new ChatMessage(Role.ASSISTANT, "world"), "fake-model", true);
        writer.close();

        List<ChatMessage> restored = SessionLoader.load(sessionDir);

        assertEquals(2, restored.size());
        assertEquals("hello", restored.get(0).content());
        assertEquals("world", restored.get(1).content());
    }

    /**
     * compact 标记之后应只恢复标记后的消息。
     */
    @Test
    public void compactMarkerShouldResetVisibleHistory() throws Exception {
        Path sessionDir = Files.createTempDirectory("mewcode-session-compact");
        Writer writer = Writer.create(sessionDir);
        writer.append(new ChatMessage(Role.USER, "old"), "fake-model", true);
        writer.writeCompactMarker();
        writer.append(new ChatMessage(Role.USER, "new"), "fake-model", true);
        writer.close();

        List<ChatMessage> restored = SessionLoader.load(sessionDir);

        assertEquals(1, restored.size());
        assertEquals("new", restored.get(0).content());
    }

    /**
     * 坏行应被跳过，不影响其余消息恢复。
     */
    @Test
    public void badLineShouldBeSkipped() throws Exception {
        Path sessionDir = Files.createTempDirectory("mewcode-session-bad");
        Writer writer = Writer.create(sessionDir);
        writer.append(new ChatMessage(Role.USER, "ok"), "fake-model", true);
        Files.writeString(sessionDir.resolve("conversation.jsonl"), "{bad json\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        writer.append(new ChatMessage(Role.ASSISTANT, "still ok"), "fake-model", true);
        writer.close();

        List<ChatMessage> restored = SessionLoader.load(sessionDir);

        assertEquals(2, restored.size());
    }

    /**
     * 孤立的 assistant tool_calls 末尾应被截断。
     */
    @Test
    public void orphanedToolCallsShouldBeTruncated() throws Exception {
        Path sessionDir = Files.createTempDirectory("mewcode-session-orphan");
        Writer writer = Writer.create(sessionDir);
        writer.append(new ChatMessage(Role.USER, "read"), "fake-model", true);
        writer.append(new ChatMessage(Role.ASSISTANT, "", List.of(new ToolCall("c1", "read_file", "{}")), List.of()),
                "fake-model", true);
        writer.close();

        List<ChatMessage> restored = SessionLoader.load(sessionDir);

        assertEquals(1, restored.size());
        assertEquals(Role.USER, restored.get(0).role());
    }

    /**
     * 新格式会话应能列出，旧格式目录应被跳过。
     */
    @Test
    public void listShouldSkipOldFormat() throws Exception {
        Path sessionsDir = Files.createTempDirectory("mewcode-session-list");
        Path valid = sessionsDir.resolve("20260613-120000-abcd");
        Path old = sessionsDir.resolve("1717000000-abc12345");
        Files.createDirectories(valid);
        Files.createDirectories(old);
        Files.writeString(valid.resolve("conversation.jsonl"), "{\"role\":\"user\",\"content\":\"hello\",\"ts\":1}\n",
                StandardCharsets.UTF_8);
        Files.writeString(old.resolve("conversation.jsonl"), "{\"role\":\"user\",\"content\":\"old\",\"ts\":1}\n",
                StandardCharsets.UTF_8);

        List<SessionInfo> sessions = SessionList.list(sessionsDir);

        assertEquals(1, sessions.size());
        assertEquals("20260613-120000-abcd", sessions.get(0).id());
    }

    /**
     * 过期的新格式目录应被清理。
     */
    @Test
    public void cleanerShouldDeleteExpiredNewFormatSessions() throws Exception {
        Path sessionsDir = Files.createTempDirectory("mewcode-session-clean");
        Path expired = sessionsDir.resolve("20200101-000000-dead");
        Path fresh = sessionsDir.resolve("20990101-000000-live");
        Files.createDirectories(expired);
        Files.createDirectories(fresh);
        Files.writeString(expired.resolve("conversation.jsonl"), "x", StandardCharsets.UTF_8);
        Files.writeString(fresh.resolve("conversation.jsonl"), "x", StandardCharsets.UTF_8);

        SessionCleaner.cleanExpired(sessionsDir, Duration.ofDays(30));

        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(fresh));
    }
}
