package dev.mewcode.agent.runtime;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.Usage;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.tool.Registry;
import dev.mewcode.agent.tool.Result;
import dev.mewcode.agent.tool.Tool;
import dev.mewcode.agent.tool.ToolContext;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ToolAgentTest {
    @Test
    public void runsOneToolRoundAndFinalAnswer() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c1", "echo_tool", "{\"text\":\"hi\"}"))));
        provider.responses.add(new ChatResponse("done", Collections.emptyList(), new Usage(10, 5, 0, 0)));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "use tool"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> {
        }, new NoopDisplay());

        assertEquals("done", answer);
        assertEquals(2, provider.requests);
        assertEquals(dev.mewcode.agent.llm.Role.TOOL, messages.get(2).role());
    }

    @Test
    public void injectsPlanReminderIntoModelRequest() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("计划完成", Collections.emptyList()));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "当前是什么模式"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> {
        }, new NoopDisplay(), ToolAgent.Mode.PLAN);

        assertEquals("计划完成", answer);
        assertTrue(provider.lastRequest.reminder().contains("<system-reminder>"));
        assertTrue(provider.lastRequest.systemPrompt().contains("You are MewCode"));
        assertTrue(provider.lastRequest.environmentInfo().contains("Environment Information:"));
    }

    @Test
    public void remembersEmptyWorkspaceAcrossRunsAndSkipsReadonlyTools() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(
                new ToolCall("c1", "glob", "{\"pattern\":\"**/*\"}"))));
        provider.responses.add(new ChatResponse("第一轮计划", Collections.emptyList()));
        provider.responses.add(new ChatResponse("第二轮细化计划", Collections.emptyList()));
        Registry registry = new Registry();
        registry.register(new EmptyGlobTool());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "我想做一个电商系统"));

        ToolAgent agent = new ToolAgent(provider, registry);
        String firstAnswer = agent.run(messages, text -> {
        }, new NoopDisplay(), ToolAgent.Mode.PLAN);
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "第一步"));
        String secondAnswer = agent.run(messages, text -> {
        }, new NoopDisplay(), ToolAgent.Mode.PLAN);

        assertEquals("第一轮计划", firstAnswer);
        assertEquals("第二轮细化计划", secondAnswer);
        assertEquals(3, provider.requests);
        assertTrue(containsSystemMessage(messages, Prompt.EMPTY_WORKSPACE_MARKER));
        assertEquals(1, provider.allTools.get(0).size());
        assertEquals(0, provider.allTools.get(1).size());
        assertEquals(0, provider.allTools.get(2).size());
    }

    private boolean containsSystemMessage(List<ChatMessage> messages, String expected) {
        for (ChatMessage message : messages) {
            if (message.role() == dev.mewcode.agent.llm.Role.SYSTEM && expected.equals(message.content())) {
                return true;
            }
        }
        return false;
    }

    private Registry testRegistry() {
        Registry registry = new Registry();
        registry.register(new EchoTool());
        return registry;
    }

    private static final class FakeProvider implements LlmProvider {
        private final List<ChatResponse> responses = new ArrayList<ChatResponse>();
        private final List<List<ToolDefinition>> allTools = new ArrayList<List<ToolDefinition>>();
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
            allTools.add(new ArrayList<ToolDefinition>(request.tools()));
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

    private static final class EmptyGlobTool implements Tool {
        @Override
        public String name() {
            return "glob";
        }

        @Override
        public String description() {
            return "test glob";
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
            return Result.ok("无匹配");
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
