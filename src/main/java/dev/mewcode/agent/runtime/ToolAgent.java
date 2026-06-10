package dev.mewcode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.prompt.Prompt;
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
    public enum Mode {
        NORMAL,
        PLAN
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean DEBUG_TOOL_CALLS = Boolean.parseBoolean(
            System.getenv().getOrDefault("MEWCODE_DEBUG_TOOL_CALLS", "false"));
    private static final int MAX_ITERATIONS = 12;
    private static final int MAX_UNKNOWN_ROUNDS = 3;
    private static final int MAX_REPEATED_PLAN_ROUNDS = 2;

    private final LlmProvider provider;
    private final Registry registry;

    public ToolAgent(LlmProvider provider, Registry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display) throws Exception {
        return run(messages, callback, display, Mode.NORMAL);
    }

    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display, Mode mode) throws Exception {
        int unknownRounds = 0;
        String previousPlanSignature = null;
        int repeatedPlanRounds = 0;
        boolean emptyWorkspaceDetected = hasEmptyWorkspaceMarker(messages);

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            debug("starting iteration " + iteration + " in mode " + mode);
            List<ToolDefinition> definitions = selectDefinitions(mode, emptyWorkspaceDetected);
            ChatResponse response = provider.streamChat(buildRequestMessages(messages, mode, emptyWorkspaceDetected), definitions, callback);
            List<ToolCall> toolCalls = response.toolCalls();
            debug("iteration " + iteration + " tool call count = " + toolCalls.size());

            if (toolCalls.isEmpty()) {
                String finalText = ensureAssistantText(response.text(), mode);
                messages.add(new ChatMessage(Role.ASSISTANT, finalText));
                return finalText;
            }

            messages.add(new ChatMessage(Role.ASSISTANT, response.text(), toolCalls, Collections.<ToolResult>emptyList()));
            List<ToolResult> results = executeCalls(toolCalls, display, mode);
            messages.add(new ChatMessage(Role.TOOL, "", Collections.<ToolCall>emptyList(), results));

            if (mode == Mode.PLAN) {
                if (looksLikeEmptyWorkspace(toolCalls, results)) {
                    emptyWorkspaceDetected = true;
                    addEmptyWorkspaceMarker(messages);
                }

                String currentSignature = buildPlanSignature(toolCalls, results);
                if (currentSignature.equals(previousPlanSignature)) {
                    repeatedPlanRounds++;
                } else {
                    repeatedPlanRounds = 0;
                    previousPlanSignature = currentSignature;
                }
                if (repeatedPlanRounds >= MAX_REPEATED_PLAN_ROUNDS - 1) {
                    String notice = "计划模式下检测到重复的只读探测，当前工作区信息不足；请直接给出计划，或用 /do 开始执行。";
                    callback.onText(notice);
                    messages.add(new ChatMessage(Role.ASSISTANT, notice));
                    return notice;
                }
            }

            if (allUnknown(toolCalls)) {
                unknownRounds++;
                if (unknownRounds >= MAX_UNKNOWN_ROUNDS) {
                    String notice = "已连续多轮请求未注册工具，自动停止本轮执行。";
                    callback.onText(notice);
                    messages.add(new ChatMessage(Role.ASSISTANT, notice));
                    return notice;
                }
            } else {
                unknownRounds = 0;
            }
        }

        String capped = "已达到最大迭代轮数，自动停止本轮执行。";
        callback.onText(capped);
        messages.add(new ChatMessage(Role.ASSISTANT, capped));
        return capped;
    }

    private List<ChatMessage> buildRequestMessages(List<ChatMessage> messages, Mode mode, boolean emptyWorkspaceDetected) {
        List<ChatMessage> requestMessages = new ArrayList<ChatMessage>(messages);
        if (mode == Mode.PLAN) {
            requestMessages.add(new ChatMessage(Role.SYSTEM, Prompt.PLAN_MODE_REMINDER));
            if (emptyWorkspaceDetected) {
                requestMessages.add(new ChatMessage(Role.SYSTEM, Prompt.EMPTY_WORKSPACE_REMINDER));
            }
        }
        return requestMessages;
    }

    private List<ToolDefinition> selectDefinitions(Mode mode, boolean emptyWorkspaceDetected) {
        if (mode == Mode.PLAN) {
            if (emptyWorkspaceDetected) {
                return Collections.emptyList();
            }
            return registry.readOnlyDefinitions();
        }
        return registry.definitions();
    }

    private boolean hasEmptyWorkspaceMarker(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message.role() == Role.SYSTEM && Prompt.EMPTY_WORKSPACE_MARKER.equals(message.content())) {
                return true;
            }
        }
        return false;
    }

    private void addEmptyWorkspaceMarker(List<ChatMessage> messages) {
        if (!hasEmptyWorkspaceMarker(messages)) {
            messages.add(new ChatMessage(Role.SYSTEM, Prompt.EMPTY_WORKSPACE_MARKER));
        }
    }

    private String ensureAssistantText(String text, Mode mode) {
        if (text != null && !text.trim().isEmpty()) {
            return text;
        }
        if (mode == Mode.PLAN) {
            return "已完成计划整理，但模型没有输出可见文本。";
        }
        return "本轮已完成执行，但模型没有输出最终文本。";
    }

    private boolean allUnknown(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            if (registry.get(call.name()).isPresent()) {
                return false;
            }
        }
        return true;
    }

    private List<ToolResult> executeCalls(List<ToolCall> calls, ToolDisplay display, Mode mode) {
        List<ToolResult> results = new ArrayList<ToolResult>();
        for (ToolCall call : calls) {
            String args = previewArgs(call);
            debug("executing tool " + call.name() + " with args " + args);
            display.onToolStart(call.name(), args);
            Result result;
            if (mode == Mode.PLAN && !registry.isReadOnly(call.name())) {
                result = Result.error("计划模式下禁止执行非只读工具: " + call.name());
            } else {
                result = executeWithTimeout(call);
            }
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

    private String buildPlanSignature(List<ToolCall> calls, List<ToolResult> results) {
        StringBuilder signature = new StringBuilder();
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            signature.append(call.name()).append('|').append(call.inputJson()).append('|');
            if (i < results.size()) {
                ToolResult result = results.get(i);
                signature.append(result.isError()).append('|').append(result.content());
            }
            signature.append('\n');
        }
        return signature.toString();
    }

    private boolean looksLikeEmptyWorkspace(List<ToolCall> calls, List<ToolResult> results) {
        if (calls.isEmpty() || calls.size() != results.size()) {
            return false;
        }
        for (int i = 0; i < calls.size(); i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            if (!registry.isReadOnly(call.name())) {
                return false;
            }
            if (!isEmptyWorkspaceSignal(call.name(), result)) {
                return false;
            }
        }
        return true;
    }

    private boolean isEmptyWorkspaceSignal(String toolName, ToolResult result) {
        String content = result.content() == null ? "" : result.content();
        if ("glob".equals(toolName)) {
            return !result.isError() && content.contains("无匹配");
        }
        if ("grep".equals(toolName)) {
            return !result.isError() && content.contains("无命中");
        }
        if ("read_file".equals(toolName)) {
            return result.isError() && content.contains("文件不存在");
        }
        return false;
    }

    private void debug(String message) {
        if (DEBUG_TOOL_CALLS) {
            System.err.println("[MewCode][ToolAgent] " + message);
        }
    }
}
