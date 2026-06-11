package dev.mewcode.agent.runtime;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.llm.Usage;
import dev.mewcode.agent.permission.Mode;
import dev.mewcode.agent.permission.Outcome;
import dev.mewcode.agent.permission.PermissionEngine;
import dev.mewcode.agent.tool.Registry;
import dev.mewcode.agent.tool.Result;
import dev.mewcode.agent.tool.Tool;
import dev.mewcode.agent.tool.ToolContext;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolAgentTest {
    static {
        System.setProperty("mewcode.userSettings", "__tests__/missing-user-settings.yaml");
    }

    @Test
    public void runsMultiTurnToolLoopUntilFinalAnswer() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c1", "echo_tool", "{\"text\":\"hi\"}"))));
        provider.responses.add(new ChatResponse("done", Collections.<ToolCall>emptyList(), new Usage(10, 5, 0, 0)));

        Registry registry = new Registry();
        registry.register(new EchoTool());
        Path root = Files.createTempDirectory("mewcode-agent-loop");
        PermissionEngine engine = PermissionEngine.create(root);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(Role.USER, "use tool"));

        String answer = new ToolAgent(provider, registry, engine, new AllowApproval()).run(messages, text -> {
        }, new NoopDisplay(), Mode.DEFAULT);

        assertEquals("done", answer);
        assertEquals(2, provider.requests);
        assertEquals(Role.ASSISTANT, messages.get(1).role());
        assertEquals(Role.TOOL, messages.get(2).role());
        assertEquals(Role.ASSISTANT, messages.get(3).role());
    }

    @Test
    public void deniesOutsideWriteAndContinues() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c1", "write_file", "{\"path\":\"..\\\\outside.txt\",\"content\":\"x\"}"))));
        provider.responses.add(new ChatResponse("handled", Collections.<ToolCall>emptyList()));

        Registry registry = Registry.defaultRegistry();
        Path root = Files.createTempDirectory("mewcode-agent-perm");
        PermissionEngine engine = PermissionEngine.create(root);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(Role.USER, "write outside"));

        String answer = new ToolAgent(provider, registry, engine, new AllowApproval()).run(messages, text -> {
        }, new NoopDisplay(), Mode.DEFAULT);

        assertEquals("handled", answer);
        ToolResult denied = messages.get(2).toolResults().get(0);
        assertTrue(denied.isError());
        assertTrue(denied.content().contains("项目目录之外"));
    }

    @Test
    public void asksForWriteInDefaultMode() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c1", "write_file", "{\"path\":\"note.txt\",\"content\":\"ok\"}"))));
        provider.responses.add(new ChatResponse("done", Collections.<ToolCall>emptyList()));

        Registry registry = Registry.defaultRegistry();
        Path root = Files.createTempDirectory("mewcode-agent-ask");
        PermissionEngine engine = PermissionEngine.create(root);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(Role.USER, "write note"));

        RecordingApproval approval = new RecordingApproval(Outcome.DENY_ONCE);
        String answer = new ToolAgent(provider, registry, engine, approval).run(messages, text -> {
        }, new NoopDisplay(), Mode.DEFAULT);

        assertEquals("done", answer);
        assertEquals("write_file", approval.requestedCallName);
        ToolResult denied = messages.get(2).toolResults().get(0);
        assertTrue(denied.isError());
    }

    @Test
    public void allowForeverShouldPersistAndSkipApprovalNextTime() throws Exception {
        FakeProvider firstProvider = new FakeProvider();
        firstProvider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c1", "write_file", "{\"path\":\"note.txt\",\"content\":\"ok\"}"))));
        firstProvider.responses.add(new ChatResponse("done", Collections.<ToolCall>emptyList()));

        Registry registry = Registry.defaultRegistry();
        Path root = Files.createTempDirectory("mewcode-agent-persist");
        PermissionEngine engine = PermissionEngine.create(root);
        List<ChatMessage> firstMessages = new ArrayList<ChatMessage>();
        firstMessages.add(new ChatMessage(Role.USER, "write note"));

        RecordingApproval firstApproval = new RecordingApproval(Outcome.ALLOW_FOREVER);
        String firstAnswer = new ToolAgent(firstProvider, registry, engine, firstApproval).run(firstMessages, text -> {
        }, new NoopDisplay(), Mode.DEFAULT);

        assertEquals("done", firstAnswer);
        assertEquals("write_file", firstApproval.requestedCallName);
        assertTrue(Files.exists(root.resolve(".mewcode").resolve("settings.local.yaml")));

        FakeProvider secondProvider = new FakeProvider();
        secondProvider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c2", "write_file", "{\"path\":\"note.txt\",\"content\":\"again\"}"))));
        secondProvider.responses.add(new ChatResponse("done again", Collections.<ToolCall>emptyList()));

        PermissionEngine reloaded = PermissionEngine.create(root);
        List<ChatMessage> secondMessages = new ArrayList<ChatMessage>();
        secondMessages.add(new ChatMessage(Role.USER, "write note again"));
        RecordingApproval secondApproval = new RecordingApproval(Outcome.DENY_ONCE);

        String secondAnswer = new ToolAgent(secondProvider, registry, reloaded, secondApproval).run(secondMessages,
                text -> {
                }, new NoopDisplay(), Mode.DEFAULT);

        assertEquals("done again", secondAnswer);
        assertTrue(secondApproval.requestedCallName == null);
        ToolResult toolResult = secondMessages.get(2).toolResults().get(0);
        assertFalse(toolResult.isError());
    }

    @Test
    public void executesConsecutiveReadonlyToolsConcurrentlyAndKeepsOrder() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", asCalls(
                new ToolCall("ro1", "slow_read_one", "{}"),
                new ToolCall("ro2", "slow_read_two", "{}"),
                new ToolCall("rw1", "slow_write", "{}"))));
        provider.responses.add(new ChatResponse("all done", Collections.<ToolCall>emptyList()));

        AtomicInteger concurrentReads = new AtomicInteger();
        AtomicInteger peakConcurrentReads = new AtomicInteger();
        AtomicLong readFinishedAt = new AtomicLong();
        AtomicLong writeStartedAt = new AtomicLong();

        Registry registry = new Registry();
        registry.register(new SlowTool("slow_read_one", true, concurrentReads, peakConcurrentReads, readFinishedAt,
                writeStartedAt, "read-1"));
        registry.register(new SlowTool("slow_read_two", true, concurrentReads, peakConcurrentReads, readFinishedAt,
                writeStartedAt, "read-2"));
        registry.register(new SlowTool("slow_write", false, concurrentReads, peakConcurrentReads, readFinishedAt,
                writeStartedAt, "write-1"));

        Path root = Files.createTempDirectory("mewcode-agent-batch");
        PermissionEngine engine = PermissionEngine.create(root);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(Role.USER, "run batch"));

        String answer = new ToolAgent(provider, registry, engine, new AllowApproval()).run(messages, text -> {
        }, new NoopDisplay(), Mode.ACCEPT_EDITS);

        assertEquals("all done", answer);
        assertTrue(peakConcurrentReads.get() >= 2);
        assertTrue(writeStartedAt.get() >= readFinishedAt.get());
        ChatMessage toolMessage = messages.get(2);
        assertEquals("ro1", toolMessage.toolResults().get(0).toolCallId());
        assertEquals("ro2", toolMessage.toolResults().get(1).toolCallId());
        assertEquals("rw1", toolMessage.toolResults().get(2).toolCallId());
    }

    private List<ToolCall> asCalls(ToolCall first, ToolCall second, ToolCall third) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        calls.add(first);
        calls.add(second);
        calls.add(third);
        return calls;
    }

    private static final class FakeProvider implements LlmProvider {
        private final List<ChatResponse> responses = new ArrayList<ChatResponse>();
        private LlmRequest lastRequest;
        private int requests;

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
            requests++;
            lastRequest = request;
            ChatResponse response = responses.remove(0);
            if (!response.text().isEmpty()) {
                callback.onText(response.text());
            }
            return response;
        }
    }

    private static final class EchoTool implements Tool {
        @Override
        public String name() {
            return "echo_tool";
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public Map<String, Object> parameters() {
            return Collections.emptyMap();
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        @Override
        public Result execute(ToolContext context, String inputJson) {
            return Result.ok(inputJson);
        }
    }

    private static final class SlowTool implements Tool {
        private final String name;
        private final boolean readOnly;
        private final AtomicInteger concurrentReads;
        private final AtomicInteger peakConcurrentReads;
        private final AtomicLong readFinishedAt;
        private final AtomicLong writeStartedAt;
        private final String resultText;

        private SlowTool(String name, boolean readOnly, AtomicInteger concurrentReads, AtomicInteger peakConcurrentReads,
                AtomicLong readFinishedAt, AtomicLong writeStartedAt, String resultText) {
            this.name = name;
            this.readOnly = readOnly;
            this.concurrentReads = concurrentReads;
            this.peakConcurrentReads = peakConcurrentReads;
            this.readFinishedAt = readFinishedAt;
            this.writeStartedAt = writeStartedAt;
            this.resultText = resultText;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return name;
        }

        @Override
        public Map<String, Object> parameters() {
            return Collections.emptyMap();
        }

        @Override
        public boolean readOnly() {
            return readOnly;
        }

        @Override
        public Result execute(ToolContext context, String inputJson) {
            if (readOnly) {
                int running = concurrentReads.incrementAndGet();
                updatePeak(running);
                sleepQuietly(200L);
                concurrentReads.decrementAndGet();
                readFinishedAt.updateAndGet(previous -> Math.max(previous, System.nanoTime()));
                return Result.ok(resultText);
            }
            writeStartedAt.compareAndSet(0L, System.nanoTime());
            sleepQuietly(50L);
            return Result.ok(resultText);
        }

        private void updatePeak(int running) {
            while (true) {
                int currentPeak = peakConcurrentReads.get();
                if (running <= currentPeak) {
                    return;
                }
                if (peakConcurrentReads.compareAndSet(currentPeak, running)) {
                    return;
                }
            }
        }

        private void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class AllowApproval implements ApprovalHandler {
        @Override
        public Outcome requestApproval(ToolCall call, String argsPreview, String reason) {
            return Outcome.ALLOW_ONCE;
        }
    }

    private static final class RecordingApproval implements ApprovalHandler {
        private final Outcome outcome;
        private String requestedCallName;

        private RecordingApproval(Outcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public Outcome requestApproval(ToolCall call, String argsPreview, String reason) {
            this.requestedCallName = call.name();
            return outcome;
        }
    }

    private static final class NoopDisplay implements ToolDisplay {
        @Override
        public void onToolStart(String name, String args) {
        }

        @Override
        public void onToolEnd(String name, String args, String result, boolean error) {
        }
    }
}
