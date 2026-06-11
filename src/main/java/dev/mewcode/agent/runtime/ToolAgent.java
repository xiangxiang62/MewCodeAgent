package dev.mewcode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.prompt.EnvironmentInfo;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.prompt.Reminder;
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

/**
 * 串联模型、工具和会话历史的主执行器。
 */
public final class ToolAgent {
    /**
     * 代理当前所处的执行模式。
     */
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
    private static final int PLAN_REMINDER_INTERVAL = 4;

    private final LlmProvider provider;
    private final Registry registry;

    /**
     * 创建一个代理实例。
     */
    public ToolAgent(LlmProvider provider, Registry registry) {
        this.provider = provider;
        this.registry = registry;
    }

    /**
     * 以普通模式运行一轮代理流程。
     */
    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display) throws Exception {
        return run(messages, callback, display, Mode.NORMAL);
    }

    /**
     * 按指定模式执行多轮“模型决策 -> 工具调用 -> 结果回灌”循环。
     */
    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display, Mode mode)
            throws Exception {
        int unknownRounds = 0;
        String previousPlanSignature = null;
        int repeatedPlanRounds = 0;
        boolean emptyWorkspaceDetected = hasEmptyWorkspaceMarker(messages);
        String systemPrompt = Prompt.buildSystemPrompt();
        String environmentInfo = EnvironmentInfo.gather("0.1.0-SNAPSHOT", provider.model()).render();

        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            debug("starting iteration " + iteration + " in mode " + mode);
            List<ToolDefinition> definitions = selectDefinitions(mode, emptyWorkspaceDetected);
            String reminder = buildReminder(mode, emptyWorkspaceDetected, iteration);
            ChatResponse response = provider.streamChat(
                    new LlmRequest(buildRequestMessages(messages), definitions, systemPrompt, environmentInfo, reminder),
                    callback);
            List<ToolCall> toolCalls = response.toolCalls();
            debug("iteration " + iteration + " tool call count = " + toolCalls.size());

            if (toolCalls.isEmpty()) {
                String finalText = ensureAssistantText(response.text(), mode);
                messages.add(new ChatMessage(Role.ASSISTANT, finalText));
                return finalText;
            }

            messages.add(new ChatMessage(Role.ASSISTANT, response.text(), toolCalls,
                    Collections.<ToolResult>emptyList()));
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

    /**
     * 为本轮请求复制一份消息快照，避免外部列表在请求途中继续变化。
     */
    private List<ChatMessage> buildRequestMessages(List<ChatMessage> messages) {
        return new ArrayList<ChatMessage>(messages);
    }

    /**
     * 生成本轮 reminder；计划模式和空工作区提示会在这里合并。
     */
    private String buildReminder(Mode mode, boolean emptyWorkspaceDetected, int iteration) {
        List<String> parts = new ArrayList<String>();
        if (mode == Mode.PLAN) {
            boolean full = iteration == 1 || ((iteration - 1) % PLAN_REMINDER_INTERVAL == 0);
            parts.add(Reminder.plan(full));
        }
        if (emptyWorkspaceDetected) {
            parts.add(Reminder.systemReminder(Prompt.EMPTY_WORKSPACE_REMINDER));
        }
        return joinNonEmpty(parts);
    }

    /**
     * 用空行拼接非空文本段。
     */
    private String joinNonEmpty(List<String> parts) {
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(part.trim());
        }
        return builder.toString();
    }

    /**
     * 根据模式选择当前轮允许模型调用的工具集合。
     */
    private List<ToolDefinition> selectDefinitions(Mode mode, boolean emptyWorkspaceDetected) {
        if (mode == Mode.PLAN) {
            if (emptyWorkspaceDetected) {
                return Collections.emptyList();
            }
            return registry.readOnlyDefinitions();
        }
        return registry.definitions();
    }

    /**
     * 检查会话历史里是否已经记录过空工作区标记。
     */
    private boolean hasEmptyWorkspaceMarker(List<ChatMessage> messages) {
        for (ChatMessage message : messages) {
            if (message.role() == Role.SYSTEM && Prompt.EMPTY_WORKSPACE_MARKER.equals(message.content())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 向会话历史补充空工作区标记，避免后续重复探测。
     */
    private void addEmptyWorkspaceMarker(List<ChatMessage> messages) {
        if (!hasEmptyWorkspaceMarker(messages)) {
            messages.add(new ChatMessage(Role.SYSTEM, Prompt.EMPTY_WORKSPACE_MARKER));
        }
    }

    /**
     * 兜底补齐模型没有输出文本时的可见回复。
     */
    private String ensureAssistantText(String text, Mode mode) {
        if (text != null && !text.trim().isEmpty()) {
            return text;
        }
        if (mode == Mode.PLAN) {
            return "已完成计划整理，但模型没有输出可见文本。";
        }
        return "本轮已完成执行，但模型没有输出最终文本。";
    }

    /**
     * 判断本轮工具调用是否全部未注册。
     */
    private boolean allUnknown(List<ToolCall> calls) {
        for (ToolCall call : calls) {
            if (registry.get(call.name()).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 顺序执行本轮工具调用，并把结果转换成模型可消费的结构。
     */
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

    /**
     * 为单次工具调用施加超时保护，防止卡住整个代理循环。
     */
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

    /**
     * 从工具参数中挑选一个适合展示的关键字段，减少终端噪声。
     */
    private String previewArgs(ToolCall call) {
        try {
            JsonNode root = JSON.readTree(call.inputJson());
            String[] keys = { "path", "command", "pattern", "old_string" };
            for (String key : keys) {
                JsonNode value = root.get(key);
                if (value != null && value.isTextual()) {
                    return truncate(value.asText(), 80);
                }
            }
        } catch (Exception ignored) {
            // 非法 JSON 会在工具执行结果中体现，这里只负责尽量展示摘要。
        }
        return truncate(call.inputJson(), 80);
    }

    /**
     * 截断较长参数字符串，避免终端输出过长。
     */
    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /**
     * 生成计划模式下用于检测重复探测的签名。
     */
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

    /**
     * 判断本轮只读探测是否整体指向“工作区为空”这个结论。
     */
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

    /**
     * 根据工具结果文本识别常见的空工作区信号。
     */
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

    /**
     * 在调试开关开启时输出内部日志。
     */
    private void debug(String message) {
        if (DEBUG_TOOL_CALLS) {
            System.err.println("[MewCode][ToolAgent] " + message);
        }
    }
}
