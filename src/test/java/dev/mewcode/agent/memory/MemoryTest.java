package dev.mewcode.agent.memory;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

/**
 * 验证 memory store 和 manager 的关键能力。
 */
public class MemoryTest {
    /**
     * create/update/delete 应正确改动笔记文件和索引。
     */
    @Test
    public void storeShouldCreateUpdateDeleteNotes() throws Exception {
        Path dir = Files.createTempDirectory("mewcode-memory-store");
        Store store = new Store(dir);

        store.apply(List.of(new UpdateAction("create", "project", "project_fact", "slug-a", "Title A", "Body A", null)));
        assertTrue(Files.exists(dir.resolve("project_fact_slug-a.md")));

        store.apply(List.of(new UpdateAction("update", "project", "project_fact", "slug-a", "Title B", "Body B", null)));
        assertTrue(Files.readString(dir.resolve("project_fact_slug-a.md"), StandardCharsets.UTF_8).contains("Body B"));

        store.apply(List.of(new UpdateAction("delete", "project", "project_fact", "slug-a", "Title B", "Body B", null)));
        assertTrue(!Files.exists(dir.resolve("project_fact_slug-a.md")));
    }

    /**
     * 项目级索引应排在用户级索引之前。
     */
    @Test
    public void managerShouldMergeIndexesInPriorityOrder() throws Exception {
        Path projectDir = Files.createTempDirectory("mewcode-memory-project");
        Path userDir = Files.createTempDirectory("mewcode-memory-user");
        Files.writeString(projectDir.resolve("MEMORY.md"), "PROJECT", StandardCharsets.UTF_8);
        Files.writeString(userDir.resolve("MEMORY.md"), "USER", StandardCharsets.UTF_8);

        Manager manager = new Manager(projectDir, userDir, null, "fake-model");
        String index = manager.loadIndex();

        assertTrue(index.indexOf("PROJECT") < index.indexOf("USER"));
    }

    /**
     * 异步更新应能解析模型 JSON 并写入笔记文件。
     */
    @Test
    public void managerShouldApplyAsyncUpdates() throws Exception {
        Path projectDir = Files.createTempDirectory("mewcode-memory-project-async");
        Path userDir = Files.createTempDirectory("mewcode-memory-user-async");
        FakeProvider provider = new FakeProvider("[{\"action\":\"create\",\"level\":\"project\",\"type\":\"project_fact\",\"slug\":\"demo\",\"title\":\"Demo\",\"content\":\"Remember this\"}]");
        Manager manager = new Manager(projectDir, userDir, provider, "fake-model");

        List<ChatMessage> recent = new ArrayList<ChatMessage>();
        recent.add(new ChatMessage(Role.USER, "记住这个项目用了 Spring Boot"));
        recent.add(new ChatMessage(Role.ASSISTANT, "好的"));
        manager.updateAsync(recent);
        Thread.sleep(800L);

        assertTrue(Files.exists(projectDir.resolve("project_fact_demo.md")));
    }

    /**
     * 超大索引应被截断并带提示。
     */
    @Test
    public void managerShouldTruncateLargeIndex() throws Exception {
        Path projectDir = Files.createTempDirectory("mewcode-memory-large-project");
        Path userDir = Files.createTempDirectory("mewcode-memory-large-user");
        String large = "A".repeat(30 * 1024);
        Files.writeString(projectDir.resolve("MEMORY.md"), large, StandardCharsets.UTF_8);

        Manager manager = new Manager(projectDir, userDir, null, "fake-model");
        String index = manager.loadIndex();

        assertTrue(index.contains("(index truncated)"));
    }

    /**
     * 提供一个可控的假 LLM provider。
     */
    private static final class FakeProvider implements LlmProvider {
        private final String reply;

        private FakeProvider(String reply) {
            this.reply = reply;
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public String model() {
            return "fake-model";
        }

        @Override
        public ChatResponse streamChat(LlmRequest request, StreamCallback callback) {
            callback.onText(reply);
            return new ChatResponse(reply, List.of());
        }
    }
}
