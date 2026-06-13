package dev.mewcode.agent.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.compact.Compact;
import dev.mewcode.agent.compact.Compact.CompactResult;
import dev.mewcode.agent.compact.Recovery.RecoveryState;
import dev.mewcode.agent.compact.Token;
import dev.mewcode.agent.compact.state.AutoCompactTrackingState;
import dev.mewcode.agent.compact.state.ContentReplacementState;
import dev.mewcode.agent.compact.state.SessionContext;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.PromptTooLongException;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.permission.Decision;
import dev.mewcode.agent.permission.Mode;
import dev.mewcode.agent.permission.Outcome;
import dev.mewcode.agent.permission.PermissionEngine;
import dev.mewcode.agent.prompt.EnvironmentInfo;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.prompt.Reminder;
import dev.mewcode.agent.tool.Registry;
import dev.mewcode.agent.tool.Result;
import dev.mewcode.agent.tool.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 串联模型、工具、权限系统和会话上下文管理的主执行器。
 */
public final class ToolAgent {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean DEBUG_TOOL_CALLS = Boolean.parseBoolean(
            System.getenv().getOrDefault("MEWCODE_DEBUG_TOOL_CALLS", "false"));
    private static final int MAX_ITERATIONS = 25;
    private static final int MAX_UNKNOWN_ROUNDS = 3;

    private final LlmProvider provider;
    private final Registry registry;
    private final PermissionEngine permissionEngine;
    private final ApprovalHandler approvalHandler;
    private final Compact compact;
    private final RecoveryState recoveryState;
    private final ReentrantLock runLock = new ReentrantLock();

    private long usageAnchor;
    private int usageAnchorMessageCount;

    /**
     * 创建一个带会话级上下文状态的代理实例。
     */
    public ToolAgent(
            LlmProvider provider,
            Registry registry,
            PermissionEngine permissionEngine,
            ApprovalHandler approvalHandler,
            Path workspaceRoot,
            int contextWindow) throws Exception {
        this.provider = provider;
        this.registry = registry;
        this.permissionEngine = permissionEngine;
        this.approvalHandler = approvalHandler;
        this.recoveryState = new RecoveryState();
        this.compact = new Compact(
                provider,
                Prompt.buildSystemPrompt(),
                EnvironmentInfo.gather("0.1.0-SNAPSHOT", provider.model()).render(),
                new ContentReplacementState(),
                recoveryState,
                new AutoCompactTrackingState(),
                SessionContext.create(workspaceRoot),
                contextWindow);
    }

    /**
     * 兼容旧调用方式，默认使用当前工作目录与保守上下文窗口。
     */
    public ToolAgent(LlmProvider provider, Registry registry, PermissionEngine permissionEngine,
            ApprovalHandler approvalHandler) throws Exception {
        this(provider, registry, permissionEngine, approvalHandler, Path.of("").toAbsolutePath().normalize(), 200000);
    }

    /**
     * 以默认模式运行一轮代理流程。
     */
    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display) throws Exception {
        return run(messages, callback, display, Mode.DEFAULT);
    }

    /**
     * 按指定模式执行多轮“模型决策 -> 工具调用 -> 结果回灌”循环。
     */
    public String run(List<ChatMessage> messages, StreamCallback callback, ToolDisplay display, Mode mode)
            throws Exception {
        runLock.lock();
        try {
            int unknownRounds = 0;
            boolean emergencyRetried = false;
            for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
                debug("starting iteration " + iteration + " in mode " + mode.displayName());
                List<ToolDefinition> definitions = selectDefinitions(mode);
                CompactResult managed = compact.manageContext(messages, definitions, usageAnchor, usageAnchorMessageCount);
                replaceMessages(messages, managed.messages());
                String reminder = buildReminder(mode);
                ChatResponse response;
                try {
                    response = provider.streamChat(
                            new LlmRequest(buildRequestMessages(messages), definitions, compactSystemPrompt(),
                                    compactEnvironmentInfo(), reminder),
                            callback);
                } catch (PromptTooLongException e) {
                    if (emergencyRetried) {
                        throw e;
                    }
                    emergencyRetried = true;
                    CompactResult emergency = compact.emergencyCompact(messages, definitions, usageAnchor,
                            usageAnchorMessageCount);
                    replaceMessages(messages, emergency.messages());
                    response = provider.streamChat(
                            new LlmRequest(buildRequestMessages(messages), definitions, compactSystemPrompt(),
                                    compactEnvironmentInfo(), reminder),
                            callback);
                }

                usageAnchor = Token.usageAnchor(response.usage());
                usageAnchorMessageCount = messages.size();

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
                recordReadFiles(toolCalls, results);
                messages.add(new ChatMessage(Role.TOOL, "", Collections.<ToolCall>emptyList(), results));

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
        } finally {
            runLock.unlock();
        }
    }

    /**
     * 无条件执行一次手动压缩。
     */
    public ForceCompactResult runForceCompact(List<ChatMessage> messages, Mode mode) {
        runLock.lock();
        try {
            List<ToolDefinition> definitions = selectDefinitions(mode);
            CompactResult result = compact.forceCompact(messages, definitions, usageAnchor, usageAnchorMessageCount);
            replaceMessages(messages, result.messages());
            usageAnchor = 0L;
            usageAnchorMessageCount = 0;
            return new ForceCompactResult(result.beforeTokens(), result.afterTokens(), null);
        } catch (Throwable e) {
            return new ForceCompactResult(0L, 0L, e);
        } finally {
            runLock.unlock();
        }
    }

    /**
     * 用新的消息序列整体替换当前会话历史。
     */
    private void replaceMessages(List<ChatMessage> messages, List<ChatMessage> replacement) {
        messages.clear();
        messages.addAll(replacement);
    }

    /**
     * 为本轮请求复制一份消息快照，避免外部列表在请求途中继续变化。
     */
    private List<ChatMessage> buildRequestMessages(List<ChatMessage> messages) {
        return new ArrayList<ChatMessage>(messages);
    }

    /**
     * 返回压缩器内部复用的系统提示。
     */
    private String compactSystemPrompt() {
        return Prompt.buildSystemPrompt();
    }

    /**
     * 返回压缩器内部复用的环境信息。
     */
    private String compactEnvironmentInfo() {
        return EnvironmentInfo.gather("0.1.0-SNAPSHOT", provider.model()).render();
    }

    /**
     * 生成本轮 reminder。
     */
    private String buildReminder(Mode mode) {
        if (mode == Mode.PLAN) {
            return Reminder.plan(true);
        }
        return "";
    }

    /**
     * 根据模式选择当前轮允许模型调用的工具集合。
     */
    private List<ToolDefinition> selectDefinitions(Mode mode) {
        if (mode == Mode.PLAN) {
            return registry.readOnlyDefinitions();
        }
        return registry.definitions();
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
     * 判断本轮工具调用是否全部未知。
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
     * 执行本轮工具调用，并把结果转换成模型可消费的结构。
     */
    private List<ToolResult> executeCalls(List<ToolCall> calls, ToolDisplay display, Mode mode) {
        List<ToolResult> results = new ArrayList<ToolResult>(Collections.nCopies(calls.size(), (ToolResult) null));
        int index = 0;
        while (index < calls.size()) {
            ToolCall call = calls.get(index);
            if (canRunReadOnlyBatch(call, mode)) {
                index = executeReadOnlyBatch(calls, results, display, mode, index);
            } else {
                executeSingleCall(calls, results, display, mode, index);
                index++;
            }
        }
        return results;
    }

    /**
     * 仅在非计划模式下，把连续只读工具归并成一批并发执行。
     */
    private boolean canRunReadOnlyBatch(ToolCall call, Mode mode) {
        return mode != Mode.PLAN && registry.isReadOnly(call.name());
    }

    /**
     * 并发执行一段连续只读工具，同时保证展示顺序和结果回灌顺序不变。
     */
    private int executeReadOnlyBatch(List<ToolCall> calls, List<ToolResult> results, ToolDisplay display, Mode mode,
            int start) {
        int end = start;
        while (end < calls.size() && canRunReadOnlyBatch(calls.get(end), mode)) {
            end++;
        }

        final String[] previews = new String[end - start];
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        for (int i = start; i < end; i++) {
            previews[i - start] = previewArgs(calls.get(i));
            display.onToolStart(calls.get(i).name(), previews[i - start]);
            PermissionEngine.CheckResult check = permissionEngine.check(mode, calls.get(i), true);
            if (check.decision() == Decision.DENY) {
                results.set(i, new ToolResult(calls.get(i).id(), check.reason(), true));
            }
        }

        final CountDownLatch latch = new CountDownLatch(countRunnableReads(results, start, end));
        for (int i = start; i < end; i++) {
            if (results.get(i) != null) {
                continue;
            }
            final int currentIndex = i;
            Thread worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        ToolCall call = calls.get(currentIndex);
                        Result result = runToolCall(call, mode, cancelled);
                        results.set(currentIndex, new ToolResult(call.id(), result.content(), result.isError()));
                    } finally {
                        latch.countDown();
                    }
                }
            }, "mewcode-readonly-tool-" + i);
            worker.setDaemon(true);
            worker.start();
        }

        try {
            if (latch.getCount() > 0) {
                latch.await();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        for (int i = start; i < end; i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            if (result == null) {
                result = new ToolResult(call.id(), "工具执行失败: 未收集到结果", true);
                results.set(i, result);
            }
            display.onToolEnd(call.name(), previews[i - start], result.content(), result.isError());
        }
        return end;
    }

    /**
     * 串行执行单个工具调用。
     */
    private void executeSingleCall(List<ToolCall> calls, List<ToolResult> results, ToolDisplay display, Mode mode,
            int index) {
        ToolCall call = calls.get(index);
        String args = previewArgs(call);
        ToolResult result = evaluateSingleCall(call, args, mode, display);
        display.onToolEnd(call.name(), args, result.content(), result.isError());
        results.set(index, result);
    }

    /**
     * 对单个有副作用工具做权限判定与执行。
     */
    private ToolResult evaluateSingleCall(ToolCall call, String args, Mode mode, ToolDisplay display) {
        PermissionEngine.CheckResult check = permissionEngine.check(mode, call, false);
        if (check.decision() == Decision.DENY) {
            return new ToolResult(call.id(), check.reason(), true);
        }
        if (check.decision() == Decision.ASK) {
            Outcome outcome = approvalHandler.requestApproval(call, args, check.reason());
            if (outcome == Outcome.DENY_ONCE) {
                return new ToolResult(call.id(), check.reason(), true);
            }
            if (outcome == Outcome.ALLOW_FOREVER) {
                try {
                    permissionEngine.persistLocalAllow(call);
                } catch (Exception e) {
                    debug("persist local allow failed: " + e.getMessage());
                }
            }
        }
        display.onToolStart(call.name(), args);
        Result result = runToolCall(call, mode, new AtomicBoolean(false));
        return new ToolResult(call.id(), result.content(), result.isError());
    }

    /**
     * 统计本批只读工具中仍需真正执行的数量。
     */
    private int countRunnableReads(List<ToolResult> results, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (results.get(i) == null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 按当前模式执行单个工具调用。
     */
    private Result runToolCall(ToolCall call, Mode mode, AtomicBoolean cancelled) {
        if (mode == Mode.PLAN && !registry.isReadOnly(call.name())) {
            return Result.error("计划模式下禁止执行非只读工具: " + call.name());
        }
        return executeWithTimeout(call, cancelled);
    }

    /**
     * 为单次工具调用施加超时保护，防止卡住整个代理循环。
     */
    private Result executeWithTimeout(final ToolCall call, AtomicBoolean cancelled) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        final ToolContext context = new ToolContext(cancelled);
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
     * 将成功读取的原始文件内容写入恢复状态，供压缩后恢复段使用。
     */
    private void recordReadFiles(List<ToolCall> calls, List<ToolResult> results) {
        for (int i = 0; i < calls.size() && i < results.size(); i++) {
            ToolCall call = calls.get(i);
            ToolResult result = results.get(i);
            if (call == null || result == null || result.isError() || !"read_file".equals(call.name())) {
                continue;
            }
            try {
                JsonNode root = JSON.readTree(call.inputJson());
                JsonNode pathNode = root.get("path");
                if (pathNode == null || !pathNode.isTextual()) {
                    continue;
                }
                Path path = Path.of(pathNode.asText());
                if (!Files.exists(path) || Files.isDirectory(path)) {
                    continue;
                }
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                recoveryState.recordFile(path.toAbsolutePath().normalize().toString(), raw);
            } catch (Exception e) {
                debug("record read file failed: " + e.getMessage());
            }
        }
    }

    /**
     * 从工具参数中挑选一个适合展示的关键字段，减少终端噪声。
     */
    private String previewArgs(ToolCall call) {
        try {
            JsonNode root = JSON.readTree(call.inputJson());
            String[] keys = { "path", "command", "pattern", "old_string" };
            for (int i = 0; i < keys.length; i++) {
                JsonNode value = root.get(keys[i]);
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
     * 在调试开关开启时输出内部日志。
     */
    private void debug(String message) {
        if (DEBUG_TOOL_CALLS) {
            System.err.println("[MewCode][ToolAgent] " + message);
        }
    }
}
