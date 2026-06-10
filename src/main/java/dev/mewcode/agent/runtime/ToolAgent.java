package dev.mewcode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.tool.Registry;
import dev.mewcode.agent.tool.Result;
import dev.mewcode.agent.tool.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ToolAgent {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean DEBUG_TOOL_CALLS = Boolean.parseBoolean(
            System.getenv().getOrDefault("MEWCODE_DEBUG_TOOL_CALLS", "false"));

    private final LlmProvider provider;
    private final Registry registry;

    public ToolAgent(LlmProvider provider, Registry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display) throws Exception {
        ChatResponse first = provider.streamChat(messages, registry.definitions(), callback);
        List<ToolCall> toolCalls = first.toolCalls();
        debug("first response tool call count = " + toolCalls.size());
        if (toolCalls.isEmpty()) {
            messages.add(new ChatMessage(Role.ASSISTANT, first.text()));
            return first.text();
        }

        messages.add(new ChatMessage(Role.ASSISTANT, first.text(), toolCalls, Collections.<ToolResult>emptyList()));
        List<ToolResult> results = executeCalls(toolCalls, display);
        debug("tool result count = " + results.size());
        messages.add(new ChatMessage(Role.TOOL, "", Collections.<ToolCall>emptyList(), results));

        debug("starting follow-up response after tool execution");
        ChatResponse second = provider.streamChat(messages, registry.definitions(), callback);
        String finalText = second.text();
        if (finalText.trim().isEmpty()) {
            finalText = "本轮已完成工具调用；按单轮工具上限，本次不会继续执行新的工具请求。";
            callback.onText(finalText);
        }
        messages.add(new ChatMessage(Role.ASSISTANT, finalText));
        return finalText;
    }
//hhhhhhhhh
    private List<ToolResult> executeCalls(List<ToolCall> calls, ToolDisplay display) {
        List<ToolResult> results = new ArrayList<ToolResult>();
        for (ToolCall call : calls) {
            String args = previewArgs(call);
            debug("executing tool " + call.name() + " with args " + args);
            display.onToolStart(call.name(), args);
            Result result = executeWithTimeout(call);
            display.onToolEnd(call.name(), args, result.content(), result.isError());
            results.add(new ToolResult(call.id(), result.content(), result.isError()));
        }
        return results;
    }

    private Result executeWithTimeout(final ToolCall call) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        final ToolContext context = ToolContext.fresh();
        Future<Result> future = executor.submit(new Callable<Result>() {
            @Override
            public Result call() {
                return registry.execute(context, call.name(), call.inputJson());
            }
        });
        try {
            return future.get(Registry.DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            context.cancelled().set(true);
            future.cancel(true);
            return Result.error("工具执行超时: " + call.name());
        } finally {
            executor.shutdownNow();
        }
    }

    private String previewArgs(ToolCall call) {
        try {
            JsonNode root = JSON.readTree(call.inputJson());
            String[] keys = {"path", "command", "pattern", "old_string"};
            for (String key : keys) {
                JsonNode value = root.get(key);
                if (value != null && value.isTextual()) {
                    return truncate(value.asText(), 80);
                }
            }
        } catch (Exception ignored) {
            // Invalid JSON will be surfaced by the tool execution result.
        }
        return truncate(call.inputJson(), 80);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    private void debug(String message) {
        if (DEBUG_TOOL_CALLS) {
            System.err.println("[MewCode][ToolAgent] " + message);
        }
    }
}
