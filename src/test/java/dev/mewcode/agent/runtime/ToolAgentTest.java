package dev.mewcode.agent.runtime;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ToolAgentTest {
    @Test
    public void runsOneToolRoundAndFinalAnswer() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "echo_tool", "{\"text\":\"hi\"}"))));
        provider.responses.add(new ChatResponse("done", Collections.emptyList()));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "use tool"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay());

        assertEquals("done", answer);
        assertEquals(2, provider.requests);
        assertEquals(dev.mewcode.agent.llm.Role.TOOL, messages.get(2).role());
        assertTrue(messages.get(2).toolResults().get(0).content().contains("hi"));
    }

    @Test
    public void returnsPlainTextWhenModelDoesNotCallTool() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("plain answer", Collections.emptyList()));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "say hello"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay());

        assertEquals("plain answer", answer);
        assertEquals(1, provider.requests);
        assertEquals(dev.mewcode.agent.llm.Role.ASSISTANT, messages.get(1).role());
    }

    @Test
    public void continuesExecutingToolCallsAcrossRounds() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "echo_tool", "{\"text\":\"hi\"}"))));
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c2", "echo_tool", "{\"text\":\"again\"}"))));
        provider.responses.add(new ChatResponse("all done", Collections.emptyList()));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "use tool"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay());

        assertEquals("all done", answer);
        assertEquals(3, provider.requests);
        assertEquals(2, EchoTool.executions);
    }

    @Test
    public void stopsRepeatedReadonlyProbeInPlanMode() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "echo_tool", "{\"text\":\"same\"}"))));
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c2", "echo_tool", "{\"text\":\"same\"}"))));
        Registry registry = testRegistry(true);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "plan this"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay(), ToolAgent.Mode.PLAN);

        assertTrue(answer.contains("重复的只读探测"));
        assertEquals(2, provider.requests);
        assertEquals(2, EchoTool.executions);
    }

    @Test
    public void injectsPlanReminderIntoModelRequest() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("计划完成", Collections.emptyList()));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "当前是什么模式"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay(), ToolAgent.Mode.PLAN);

        assertEquals("计划完成", answer);
        assertEquals(1, provider.requests);
        assertTrue(provider.lastMessages.get(provider.lastMessages.size() - 1).content().contains(Prompt.PLAN_MODE_REMINDER));
    }

    @Test
    public void addsEmptyWorkspaceReminderAfterReadonlyMiss() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "glob", "{\"pattern\":\"**/*\"}"))));
        provider.responses.add(new ChatResponse("这是计划", Collections.emptyList()));
        Registry registry = new Registry();
        registry.register(new EmptyGlobTool());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "我想做一个电商系统"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay(), ToolAgent.Mode.PLAN);

        assertEquals("这是计划", answer);
        assertEquals(2, provider.requests);
        assertTrue(provider.lastMessages.get(provider.lastMessages.size() - 1).content().contains(Prompt.EMPTY_WORKSPACE_REMINDER));
    }

    @Test
    public void remembersEmptyWorkspaceAcrossRunsAndSkipsReadonlyTools() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "glob", "{\"pattern\":\"**/*\"}"))));
        provider.responses.add(new ChatResponse("第一轮计划", Collections.emptyList()));
        provider.responses.add(new ChatResponse("第二轮细化计划", Collections.emptyList()));
        Registry registry = new Registry();
        registry.register(new EmptyGlobTool());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "我想做一个电商系统"));

        ToolAgent agent = new ToolAgent(provider, registry);
        String firstAnswer = agent.run(messages, text -> { }, new NoopDisplay(), ToolAgent.Mode.PLAN);
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "第一种"));
        String secondAnswer = agent.run(messages, text -> { }, new NoopDisplay(), ToolAgent.Mode.PLAN);

        assertEquals("第一轮计划", firstAnswer);
        assertEquals("第二轮细化计划", secondAnswer);
        assertEquals(3, provider.requests);
        assertTrue(containsSystemMessage(messages, Prompt.EMPTY_WORKSPACE_MARKER));
        assertTrue(provider.allTools.size() >= 3);
        assertTrue(provider.allTools.get(0).size() == 1);
        assertTrue(provider.allTools.get(1).isEmpty());
        assertTrue(provider.allTools.get(2).isEmpty());
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
        return testRegistry(false);
    }

    private Registry testRegistry(boolean readOnly) {
        EchoTool.executions = 0;
        Registry registry = new Registry();
        registry.register(new EchoTool(readOnly));
        return registry;
    }

    private static final class FakeProvider implements LlmProvider {
        private final List<ChatResponse> responses = new ArrayList<ChatResponse>();
        private final List<List<ToolDefinition>> allTools = new ArrayList<List<ToolDefinition>>();
        private List<ChatMessage> lastMessages = Collections.emptyList();
        private int requests;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ChatResponse streamChat(List<ChatMessage> messages, List<ToolDefinition> tools, StreamCallback callback) {
            requests++;
            lastMessages = new ArrayList<ChatMessage>(messages);
            allTools.add(new ArrayList<ToolDefinition>(tools));
            ChatResponse response = responses.remove(0);
            if (!response.text().isEmpty()) {
                callback.onText(response.text());
            }
            return response;
        }
    }

    private static final class EchoTool implements Tool {
        private static int executions;
        private final boolean readOnly;

        private EchoTool(boolean readOnly) {
            this.readOnly = readOnly;
        }

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
            return readOnly;
        }

        @Override
        public Result execute(ToolContext context, String inputJson) {
            executions++;
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
