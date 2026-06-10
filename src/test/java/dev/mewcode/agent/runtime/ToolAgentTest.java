package dev.mewcode.agent.runtime;

import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
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
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "echo_tool", "{\"text\":\"hi\"}"))));
        provider.responses.add(new ChatResponse("done", Collections.emptyList()));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "use tool"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay());

        assertEquals("done", answer);
        assertEquals(3, provider.calls);
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
    public void doesNotExecuteSecondRoundToolCalls() throws Exception {
        FakeProvider provider = new FakeProvider();
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c1", "echo_tool", "{\"text\":\"hi\"}"))));
        provider.responses.add(new ChatResponse("", Collections.singletonList(new ToolCall("c2", "echo_tool", "{\"text\":\"again\"}"))));
        Registry registry = testRegistry();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(dev.mewcode.agent.llm.Role.USER, "use tool"));

        String answer = new ToolAgent(provider, registry).run(messages, text -> { }, new NoopDisplay());

        assertTrue(answer.contains("单轮工具上限"));
        assertEquals(2, provider.requests);
        assertEquals(1, EchoTool.executions);
    }

    private Registry testRegistry() {
        EchoTool.executions = 0;
        Registry registry = new Registry();
        registry.register(new EchoTool());
        return registry;
    }

    private static final class FakeProvider implements LlmProvider {
        private final List<ChatResponse> responses = new ArrayList<ChatResponse>();
        private int requests;
        private int calls;

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public ChatResponse streamChat(List<ChatMessage> messages, List<ToolDefinition> tools, StreamCallback callback) {
            requests++;
            ChatResponse response = responses.remove(0);
            if (!response.text().isEmpty()) {
                callback.onText(response.text());
            }
            calls = messages.size();
            return response;
        }
    }

    private static final class EchoTool implements Tool {
        private static int executions;

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
        public Result execute(ToolContext context, String inputJson) {
            executions++;
            return Result.ok(inputJson);
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
