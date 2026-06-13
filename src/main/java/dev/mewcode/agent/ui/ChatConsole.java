package dev.mewcode.agent.ui;

import dev.mewcode.agent.compact.state.SessionContext;
import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.mcp.McpStatus;
import dev.mewcode.agent.memory.Manager;
import dev.mewcode.agent.permission.Mode;
import dev.mewcode.agent.permission.Outcome;
import dev.mewcode.agent.permission.PermissionEngine;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.prompt.Reminder;
import dev.mewcode.agent.runtime.ApprovalHandler;
import dev.mewcode.agent.runtime.ForceCompactResult;
import dev.mewcode.agent.runtime.ToolAgent;
import dev.mewcode.agent.runtime.ToolDisplay;
import dev.mewcode.agent.session.SessionInfo;
import dev.mewcode.agent.session.SessionList;
import dev.mewcode.agent.session.SessionLoader;
import dev.mewcode.agent.session.Writer;
import dev.mewcode.agent.tool.Registry;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Widget;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端交互界面，负责用户输入、模式切换、工具展示和人工确认。
 */
public final class ChatConsole {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String GRAY = "\u001B[90m";
    private static final String CLEAR_LINE = "\u001B[2K";
    private static final String SHIFT_TAB = "\u001B[Z";
    private static final String ALT_M = "\u001Bm";
    private static final String ALT_SHIFT_M = "\u001BM";

    private final String appName;
    private final Path configPath;
    private final LlmConfig llmConfig;
    private final PermissionEngine permissionEngine;
    private final Path projectRoot;
    private final McpStatus mcpStatus;
    private final String instructionText;
    private final Manager memoryManager;
    private final Path sessionsDir;

    private ToolAgent sharedAgent;
    private String memoryText;
    private SessionContext sessionContext;
    private Writer sessionWriter;

    /**
     * 审批按键后续字节读取器，便于在单元测试中模拟不同终端键序列。
     */
    interface ApprovalByteReader {
        int read(long timeoutMillis) throws Exception;
    }

    /**
     * 创建终端会话对象。
     */
    public ChatConsole(String appName, Path configPath, LlmConfig llmConfig, PermissionEngine permissionEngine,
            Path projectRoot, McpStatus mcpStatus, String instructionText, Manager memoryManager, String memoryText,
            SessionContext sessionContext, Writer sessionWriter) {
        this.appName = appName;
        this.configPath = configPath;
        this.llmConfig = llmConfig;
        this.permissionEngine = permissionEngine;
        this.projectRoot = projectRoot;
        this.mcpStatus = mcpStatus;
        this.instructionText = instructionText == null ? "" : instructionText;
        this.memoryManager = memoryManager;
        this.memoryText = memoryText == null ? "" : memoryText;
        this.sessionContext = sessionContext;
        this.sessionWriter = sessionWriter;
        this.sessionsDir = projectRoot.resolve(".mewcode").resolve("sessions");
    }

    /**
     * 启动交互循环，持续读取用户输入并驱动代理执行。
     */
    public void run(List<ChatMessage> messages, LlmProvider provider, Registry registry) throws Exception {
        final ModeState modeState = new ModeState(permissionEngine.startMode());
        try (Terminal terminal = TerminalBuilder.builder()
                .name(appName)
                .system(true)
                .encoding(StandardCharsets.UTF_8)
                .build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName(appName)
                    .build();
            installModeKeyBinding(reader, modeState);
            installMcpStatusListener(reader, terminal);

            printHeader(terminal, provider, modeState.current());
            terminal.writer().flush();

            while (true) {
                maybeEnableEnhancedKeyboard(terminal);
                String input;
                try {
                    modeState.beginInput();
                    input = reader.readLine(renderPrompt(modeState.current()));
                } catch (ModeSwitchException e) {
                    modeState.endInput();
                    printNotice(terminal, "模式已切换到 " + shortModeName(modeState.current()) + " "
                            + modeDescription(modeState.current()) + "。", modeState.current());
                    continue;
                } catch (UserInterruptException | EndOfFileException e) {
                    terminal.writer().println();
                    return;
                } finally {
                    modeState.endInput();
                }

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                String trimmed = input.trim();
                if ("/exit".equalsIgnoreCase(trimmed) || "/quit".equalsIgnoreCase(trimmed)) {
                    return;
                }
                if ("/clear".equalsIgnoreCase(trimmed)) {
                    modeState.set(Mode.DEFAULT);
                    messages.clear();
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.buildSystemPrompt(instructionText, memoryText)));
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
                    recreateSession(messages, provider.model());
                    printNotice(terminal, "已清空上下文。", modeState.current());
                    continue;
                }
                if ("/plan".equalsIgnoreCase(trimmed)) {
                    modeState.set(Mode.PLAN);
                    ChatMessage planMessage = new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_PLAN);
                    messages.add(planMessage);
                    appendCurrentMessage(planMessage, provider.model());
                    printNotice(terminal, "已切换到 " + shortModeName(modeState.current()) + " "
                            + modeDescription(modeState.current()) + "。", modeState.current());
                    continue;
                }
                if ("/do".equalsIgnoreCase(trimmed)) {
                    modeState.set(Mode.DEFAULT);
                    ChatMessage normalMessage = new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL);
                    ChatMessage directiveMessage = new ChatMessage(Role.USER, Reminder.EXECUTE_DIRECTIVE);
                    messages.add(normalMessage);
                    messages.add(directiveMessage);
                    appendCurrentMessage(normalMessage, provider.model());
                    appendCurrentMessage(directiveMessage, provider.model());
                    printAssistantLead(terminal);
                    runAgent(messages, provider, registry, terminal, modeState.current(), modeState);
                    terminal.writer().println();
                    terminal.writer().flush();
                    continue;
                }
                if ("/compact".equalsIgnoreCase(trimmed)) {
                    ForceCompactResult result = ensureAgent(provider, registry, terminal, modeState)
                            .runForceCompact(messages, modeState.current());
                    if (result.error() != null) {
                        printNotice(terminal, "压缩失败: " + result.error().getMessage(), modeState.current());
                    } else {
                        printNotice(terminal,
                                "压缩完成，估算 token 从 " + result.before() + " 降到 " + result.after(),
                                modeState.current());
                    }
                    continue;
                }
                if ("/resume".equalsIgnoreCase(trimmed)) {
                    handleResume(messages, provider, registry, terminal, modeState);
                    continue;
                }

                ChatMessage userMessage = new ChatMessage(Role.USER, input);
                messages.add(userMessage);
                appendCurrentMessage(userMessage, provider.model());
                printAssistantLead(terminal);
                runAgent(messages, provider, registry, terminal, modeState.current(), modeState);
                terminal.writer().println();
                terminal.writer().flush();
            }
        } finally {
            if (mcpStatus != null) {
                mcpStatus.setListener(null);
            }
            if (sessionWriter != null) {
                try {
                    sessionWriter.close();
                } catch (Exception ignored) {
                    // 退出时尽力关闭即可。
                }
            }
        }
    }

    /**
     * 监听后台 MCP 状态，并在终端中增量提示。
     */
    private void installMcpStatusListener(LineReader reader, Terminal terminal) {
        if (mcpStatus == null) {
            return;
        }
        mcpStatus.setListener(summary -> {
            synchronized (terminal) {
                reader.printAbove(GRAY + "  MCP   " + safeText(summary) + RESET);
                refreshPrompt(reader);
                terminal.writer().flush();
            }
        });
    }

    /**
     * 尽量让特殊按键序列透传到终端。
     */
    private void maybeEnableEnhancedKeyboard(Terminal terminal) {
        terminal.puts(InfoCmp.Capability.keypad_xmit);
        terminal.flush();
    }

    /**
     * 安装模式切换快捷键。
     */
    private void installModeKeyBinding(final LineReader reader, final ModeState modeState) {
        Widget cycleModeWidget = new Widget() {
            @Override
            public boolean apply() {
                if (reader.getBuffer().length() > 0) {
                    reader.printAbove(GRAY + "请先提交或清空当前输入，再切换模式。" + RESET);
                    refreshPrompt(reader);
                    return true;
                }
                modeState.set(modeState.current().next());
                if (modeState.isReadingInput()) {
                    throw new ModeSwitchException();
                }
                reader.printAbove(GRAY + "模式已切换到 " + shortModeName(modeState.current()) + " "
                        + modeDescription(modeState.current()) + "。" + RESET);
                refreshPrompt(reader);
                return true;
            }
        };
        reader.getWidgets().put("cycle-mode", cycleModeWidget);
        bindAllKeyMaps(reader, new Reference("cycle-mode"), SHIFT_TAB, ALT_M, ALT_SHIFT_M);
    }

    /**
     * 强制刷新当前输入行。
     */
    private void refreshPrompt(LineReader reader) {
        try {
            reader.callWidget(LineReader.REDRAW_LINE);
        } catch (Exception ignored) {
            // 某些终端不支持时忽略。
        }
        try {
            reader.callWidget(LineReader.REDISPLAY);
        } catch (Exception ignored) {
            // 某些终端不支持时忽略。
        }
    }

    /**
     * 为所有 keymap 绑定同一组快捷键。
     */
    private void bindAllKeyMaps(LineReader reader, Reference reference, String... keySequences) {
        for (KeyMap<Binding> keyMap : reader.getKeyMaps().values()) {
            for (String keySequence : keySequences) {
                keyMap.bind(reference, keySequence);
            }
        }
    }

    /**
     * 调用代理执行当前一轮对话。
     */
    private void runAgent(List<ChatMessage> messages, LlmProvider provider, Registry registry,
            Terminal terminal, Mode mode, ModeState modeState) {
        ThinkingIndicator thinkingIndicator = null;
        try {
            ToolAgent agent = ensureAgent(provider, registry, terminal, modeState);
            thinkingIndicator = new ThinkingIndicator(terminal);
            thinkingIndicator.start();
            final ThinkingIndicator finalThinkingIndicator = thinkingIndicator;
            agent.run(messages, text -> {
                if (text != null && !text.isEmpty()) {
                    finalThinkingIndicator.markOutputStarted();
                }
                terminal.writer().print(text);
                terminal.writer().flush();
            }, new ConsoleToolDisplay(terminal, new Runnable() {
                @Override
                public void run() {
                    finalThinkingIndicator.markOutputStarted();
                }
            }), mode);
            if (memoryManager != null) {
                try {
                    memoryText = memoryManager.loadIndex();
                    agent.updateMemoryText(memoryText);
                } catch (Exception ignored) {
                    // 记忆刷新失败时不打断主流程。
                }
            }
        } catch (Exception e) {
            if (thinkingIndicator != null) {
                thinkingIndicator.stop();
            }
            terminal.writer().println();
            terminal.writer().println(RED + "请求失败: " + e.getMessage() + RESET);
            terminal.writer().flush();
            return;
        }
        if (thinkingIndicator != null) {
            thinkingIndicator.stop();
        }
    }

    /**
     * 处理 /resume，按编号恢复历史会话。
     */
    private void handleResume(List<ChatMessage> messages, LlmProvider provider, Registry registry, Terminal terminal,
            ModeState modeState) {
        try {
            if (sharedAgent != null && sharedAgent.isRunning()) {
                printNotice(terminal, "请等待当前任务完成。", modeState.current());
                return;
            }
            List<SessionInfo> sessions = SessionList.list(sessionsDir);
            if (sessions.isEmpty()) {
                printNotice(terminal, "没有可恢复的历史会话。", modeState.current());
                return;
            }
            terminal.writer().println();
            terminal.writer().println(GRAY + "历史会话：" + RESET);
            for (int i = 0; i < sessions.size(); i++) {
                SessionInfo info = sessions.get(i);
                terminal.writer().println(GRAY + "  [" + (i + 1) + "] " + RESET + safeText(info.title())
                        + GRAY + "  " + info.id() + "  " + formatAgo(info.modifiedAt()) + RESET);
            }
            terminal.writer().flush();
            LineReader selector = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName(appName)
                    .build();
            String raw = selector.readLine(GRAY + "输入编号恢复，直接回车取消" + RESET + "\n" + renderPrompt(modeState.current()));
            if (raw == null || raw.trim().isEmpty()) {
                printNotice(terminal, "已取消恢复。", modeState.current());
                return;
            }
            int index = Integer.parseInt(raw.trim()) - 1;
            if (index < 0 || index >= sessions.size()) {
                printNotice(terminal, "恢复编号无效。", modeState.current());
                return;
            }
            SessionInfo selected = sessions.get(index);
            List<ChatMessage> restored = SessionLoader.load(selected.dir());
            SessionContext restoredContext = SessionContext.open(projectRoot, selected.id());
            Writer restoredWriter = Writer.open(selected.dir());
            if (sessionWriter != null) {
                sessionWriter.close();
            }
            sessionWriter = restoredWriter;
            sessionContext = restoredContext;
            messages.clear();
            messages.addAll(restored);
            if (Duration.between(selected.modifiedAt(), Instant.now()).toHours() >= 6) {
                ChatMessage reminder = new ChatMessage(Role.SYSTEM, "提示：这是一个较早前的会话，继续前请先确认上下文是否仍然有效。");
                messages.add(reminder);
                appendCurrentMessage(reminder, provider.model());
            }
            if (memoryManager != null) {
                memoryText = memoryManager.loadIndex();
            }
            ToolAgent agent = ensureAgent(provider, registry, terminal, modeState);
            agent.updateMemoryText(memoryText);
            agent.resumeSession(restoredContext, restoredWriter, messages);
            printNotice(terminal, "已恢复会话 " + selected.id() + "，共 " + restored.size() + " 条消息。", modeState.current());
        } catch (Exception e) {
            printNotice(terminal, "恢复会话失败: " + e.getMessage(), modeState.current());
        }
    }

    /**
     * 确保当前会话只复用一个 ToolAgent。
     */
    private ToolAgent ensureAgent(LlmProvider provider, Registry registry, Terminal terminal, ModeState modeState)
            throws Exception {
        if (sharedAgent == null) {
            sharedAgent = new ToolAgent(provider, registry, permissionEngine,
                    new ConsoleApprovalHandler(terminal, modeState), projectRoot,
                    llmConfig.effectiveContextWindow(), instructionText, memoryText, memoryManager, sessionContext,
                    sessionWriter);
        }
        return sharedAgent;
    }

    /**
     * 清空上下文时重建 session，并重写系统消息。
     */
    private void recreateSession(List<ChatMessage> messages, String model) {
        try {
            if (sessionWriter != null) {
                sessionWriter.close();
            }
            sessionContext = SessionContext.create(projectRoot);
            sessionWriter = Writer.create(sessionContext.sessionDir());
            for (ChatMessage message : messages) {
                sessionWriter.append(message, model, true);
            }
            if (sharedAgent != null) {
                sharedAgent.resumeSession(sessionContext, sessionWriter, messages);
            }
        } catch (Exception ignored) {
            // 重建失败时保持交互继续。
        }
    }

    /**
     * 将新增消息写入当前会话 JSONL。
     */
    private void appendCurrentMessage(ChatMessage message, String model) {
        if (sessionWriter == null || message == null) {
            return;
        }
        try {
            sessionWriter.append(message, model, true);
        } catch (Exception ignored) {
            // 落盘失败不影响继续使用。
        }
    }

    /**
     * 打印提示信息。
     */
    private void printNotice(Terminal terminal, String message, Mode mode) {
        terminal.writer().println();
        terminal.writer().println(GRAY + message + RESET);
        terminal.writer().println(DIM + "当前模式: " + shortModeName(mode) + " | " + modeDescription(mode) + RESET);
        terminal.writer().flush();
    }

    /**
     * 渲染当前输入提示符。
     */
    private String renderPrompt(Mode mode) {
        return modePrompt(mode) + CYAN + ">" + RESET + " ";
    }

    /**
     * 输出助手前缀。
     */
    private void printAssistantLead(Terminal terminal) {
        terminal.writer().print(assistantLeadText());
        terminal.writer().flush();
    }

    /**
     * 杩斿洖鍔╂墜鍓嶇紑鏂囨湰锛屼究浜庡湪鍚屼竴涓牱寮忎笅澶氬澶嶇敤銆?
     */
    private static String assistantLeadText() {
        return BOLD + "MewCode" + RESET + GRAY + " > " + RESET;
    }

    /**
     * 返回模式徽标。
     */
    private String modeBadge(Mode mode) {
        if (mode == Mode.ACCEPT_EDITS) {
            return GREEN + "[ACCEPT EDITS]" + RESET;
        }
        if (mode == Mode.PLAN) {
            return YELLOW + "[PLAN]" + RESET;
        }
        if (mode == Mode.BYPASS) {
            return RED + "[BYPASS]" + RESET;
        }
        return CYAN + "[DEFAULT]" + RESET;
    }

    /**
     * 返回 prompt 中显示的模式名称。
     */
    private String modePrompt(Mode mode) {
        if (mode == Mode.ACCEPT_EDITS) {
            return GREEN + "accept-edits" + RESET + DIM + "(自动确认写文件)" + RESET + " ";
        }
        if (mode == Mode.PLAN) {
            return YELLOW + "plan" + RESET + DIM + "(只读规划)" + RESET + " ";
        }
        if (mode == Mode.BYPASS) {
            return RED + "bypass" + RESET + DIM + "(高权限直通)" + RESET + " ";
        }
        return CYAN + "default" + RESET + DIM + "(标准确认)" + RESET + " ";
    }

    /**
     * 返回简短模式名。
     */
    private String shortModeName(Mode mode) {
        if (mode == Mode.ACCEPT_EDITS) {
            return "ACCEPT EDITS";
        }
        if (mode == Mode.PLAN) {
            return "PLAN";
        }
        if (mode == Mode.BYPASS) {
            return "BYPASS";
        }
        return "DEFAULT";
    }

    /**
     * 返回模式说明。
     */
    private String modeDescription(Mode mode) {
        if (mode == Mode.ACCEPT_EDITS) {
            return "允许直接修改文件，执行命令仍需确认";
        }
        if (mode == Mode.PLAN) {
            return "仅允许只读工具，用于调研和规划";
        }
        if (mode == Mode.BYPASS) {
            return "大部分操作直接放行，仅保留黑名单与沙箱拦截";
        }
        return "默认模式，写入和执行命令需要确认";
    }

    /**
     * 终端审批处理器。
     */
    private final class ConsoleApprovalHandler implements ApprovalHandler {
        private final Terminal terminal;
        private final ModeState modeState;
        private int lastRenderLines;

        private ConsoleApprovalHandler(Terminal terminal, ModeState modeState) {
            this.terminal = terminal;
            this.modeState = modeState;
        }

        @Override
        public Outcome requestApproval(ToolCall call, String argsPreview, String reason) {
            Attributes previous = null;
            int selected = 0;
            lastRenderLines = 0;
            try {
                previous = terminal.enterRawMode();
                while (true) {
                    renderApproval(call, argsPreview, reason, selected);
                    String action = readApprovalAction();
                    if (action == null) {
                        continue;
                    }
                    if ("UP".equals(action)) {
                        selected = previousIndex(selected);
                        continue;
                    }
                    if ("DOWN".equals(action)) {
                        selected = nextIndex(selected);
                        continue;
                    }
                    if ("ALLOW_ONCE".equals(action)) {
                        clearApprovalFrame();
                        return Outcome.ALLOW_ONCE;
                    }
                    if ("ALLOW_FOREVER".equals(action)) {
                        clearApprovalFrame();
                        return Outcome.ALLOW_FOREVER;
                    }
                    if ("DENY_ONCE".equals(action)) {
                        clearApprovalFrame();
                        return Outcome.DENY_ONCE;
                    }
                    if ("SUBMIT".equals(action)) {
                        clearApprovalFrame();
                        return outcomeFor(selected);
                    }
                    if ("CANCEL".equals(action)) {
                        clearApprovalFrame();
                        return Outcome.DENY_ONCE;
                    }
                }
            } finally {
                if (previous != null) {
                    terminal.setAttributes(previous);
                }
            }
        }

        /**
         * 解析审批阶段的按键流。
         */
        private String readApprovalAction() {
            try {
                InputStream in = terminal.input();
                int first = in.read();
                if (first < 0) {
                    return null;
                }
                return decodeApprovalAction(first, new ApprovalByteReader() {
                    @Override
                    public int read(long timeoutMillis) throws Exception {
                        return readInputByteWithTimeout(in, timeoutMillis);
                    }
                });
            } catch (Exception e) {
                return null;
            }
        }

        /**
         * 渲染审批框内容。
         */
        private void renderApproval(ToolCall call, String argsPreview, String reason, int selected) {
            String[] lines = new String[] {
                    "",
                    BOLD + "MewCode" + RESET + GRAY + "  需要确认" + RESET,
                    GRAY + "----------------------------------------------------------------" + RESET,
                    "  模式   " + modeBadge(modeState.current()) + " " + modeDescription(modeState.current()),
                    "  工具   " + BOLD + "[" + toolLabel(call.name()) + "]" + RESET,
                    "  参数   " + DIM + safeText(argsPreview) + RESET,
                    "  原因   " + DIM + safeText(reason) + RESET,
                    "",
                    approvalOption(selected == 0, "1", "允许本次", "只执行这一次"),
                    approvalOption(selected == 1, "2", "永久允许", "写入本地规则，后续同类请求直接放行"),
                    approvalOption(selected == 2, "3", "拒绝本次", "返回拒绝结果，让模型调整方案"),
                    "",
                    GRAY + "  上下键选择 / j k / 1 2 3 / Enter 确认 / Esc 取消" + RESET
            };
            rewriteFrame(lines);
        }

        /**
         * 以覆盖方式重绘审批框。
         */
        private void rewriteFrame(String[] lines) {
            if (lastRenderLines > 0) {
                terminal.writer().print(cursorUp(lastRenderLines));
            }
            for (int i = 0; i < Math.max(lastRenderLines, lines.length); i++) {
                terminal.writer().print(CLEAR_LINE);
                if (i < lines.length) {
                    terminal.writer().print(lines[i]);
                }
                terminal.writer().println();
            }
            if (lastRenderLines > lines.length) {
                terminal.writer().print(cursorUp(lastRenderLines - lines.length));
            }
            terminal.writer().flush();
            lastRenderLines = lines.length;
        }

        /**
         * 清理审批框显示区域。
         */
        private void clearApprovalFrame() {
            if (lastRenderLines <= 0) {
                return;
            }
            terminal.writer().print(cursorUp(lastRenderLines));
            for (int i = 0; i < lastRenderLines; i++) {
                terminal.writer().print(CLEAR_LINE);
                terminal.writer().println();
            }
            terminal.writer().print(cursorUp(lastRenderLines));
            terminal.writer().flush();
            lastRenderLines = 0;
        }

        /**
         * 渲染一条审批选项。
         */
        private String approvalOption(boolean active, String index, String title, String desc) {
            String prefix = active ? CYAN + "  > " + RESET : "    ";
            String label = active ? BOLD + "[" + index + "] " + title + RESET : "[" + index + "] " + title;
            return prefix + label + GRAY + "  " + desc + RESET;
        }

        /**
         * 计算下一个选中项。
         */
        private int nextIndex(int selected) {
            return (selected + 1) % 3;
        }

        /**
         * 计算上一个选中项。
         */
        private int previousIndex(int selected) {
            return (selected + 2) % 3;
        }

        /**
         * 将选中索引映射为审批结果。
         */
        private Outcome outcomeFor(int selected) {
            if (selected == 1) {
                return Outcome.ALLOW_FOREVER;
            }
            if (selected == 2) {
                return Outcome.DENY_ONCE;
            }
            return Outcome.ALLOW_ONCE;
        }
    }

    /**
     * 控制工具调用在终端中的展示格式。
     */
    private static final class ConsoleToolDisplay implements ToolDisplay {
        private static final int SUMMARY_LINES = 6;
        private static final Map<String, String> TOOL_LABELS = createToolLabels();

        private final Terminal terminal;
        private final Runnable onVisibleOutput;
        private final Map<String, RunningTool> runningTools = new ConcurrentHashMap<String, RunningTool>();

        private ConsoleToolDisplay(Terminal terminal, Runnable onVisibleOutput) {
            this.terminal = terminal;
            this.onVisibleOutput = onVisibleOutput;
        }

        @Override
        public void onToolStart(String name, String args) {
            if (onVisibleOutput != null) {
                onVisibleOutput.run();
            }
            String key = toolKey(name, args);
            RunningTool runningTool = new RunningTool();
            runningTools.put(key, runningTool);

            terminal.writer().println();
            terminal.writer().println(GRAY + "  + 调用工具  " + RESET + BOLD + "[" + toolLabel(name) + "]" + RESET
                    + GRAY + "  " + safeText(args) + RESET);
            terminal.writer().flush();

            runningTool.start(terminal, toolLabel(name));
        }

        @Override
        public void onToolEnd(String name, String args, String result, boolean error) {
            String key = toolKey(name, args);
            RunningTool runningTool = runningTools.remove(key);
            long elapsedSeconds = runningTool == null ? 0L : runningTool.finish();
            clearTransientLine(terminal);

            String tone = error ? YELLOW : GREEN;
            String state = error ? "失败" : "完成";
            terminal.writer().println(tone + "  - " + toolLabel(name) + " " + state + RESET
                    + GRAY + "  用时 " + elapsedSeconds + "s" + RESET);

            String[] lines = result == null ? new String[0] : result.split("\\R", -1);
            int limit = Math.min(lines.length, SUMMARY_LINES);
            for (int i = 0; i < limit; i++) {
                terminal.writer().println(GRAY + "    " + trimLine(lines[i]) + RESET);
            }
            if (lines.length > SUMMARY_LINES) {
                terminal.writer().println(GRAY + "    ..." + RESET);
            }
            printAssistantLeadStatic(terminal);
        }

        /**
         * 输出工具结束后的助手前缀。
         */
        private static void printAssistantLeadStatic(Terminal terminal) {
            terminal.writer().print(assistantLeadText());
            terminal.writer().flush();
        }

        /**
         * 为运行中的工具生成唯一键。
         */
        private String toolKey(String name, String args) {
            return name + "\n" + args;
        }

        /**
         * 将工具名映射为中文标签。
         */
        private String toolLabel(String name) {
            String label = TOOL_LABELS.get(name);
            return label == null ? name : label;
        }

        /**
         * 裁剪结果行长度，避免终端刷屏。
         */
        private String trimLine(String line) {
            String text = safeText(line);
            return text.length() <= 120 ? text : text.substring(0, 117) + "...";
        }

        /**
         * 初始化内置工具中文名称。
         */
        private static Map<String, String> createToolLabels() {
            Map<String, String> labels = new HashMap<String, String>();
            labels.put("read_file", "读取文件");
            labels.put("write_file", "写入文件");
            labels.put("edit_file", "编辑文件");
            labels.put("bash", "执行命令");
            labels.put("glob", "查找文件");
            labels.put("grep", "搜索内容");
            return labels;
        }
    }

    /**
     * 在工具执行期间打印简短耗时提示。
     */
    private static final class RunningTool {
        private final long startedAt = System.currentTimeMillis();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Thread ticker;

        /**
         * 启动一个后台 ticker，定期刷新耗时。
         */
        private void start(final Terminal terminal, final String label) {
            ticker = new Thread(new Runnable() {
                @Override
                public void run() {
                    long lastPrinted = 0L;
                    while (active.get()) {
                        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
                        if (elapsedSeconds >= 1 && elapsedSeconds > lastPrinted) {
                            terminal.writer().print("\r" + CLEAR_LINE + GRAY + "    " + label + " 执行中 "
                                    + elapsedSeconds + "s" + RESET);
                            terminal.writer().flush();
                            lastPrinted = elapsedSeconds;
                        }
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "mewcode-tool-ticker");
            ticker.setDaemon(true);
            ticker.start();
        }

        /**
         * 停止 ticker，并返回总耗时秒数。
         */
        private long finish() {
            active.set(false);
            if (ticker != null) {
                ticker.interrupt();
                try {
                    ticker.join(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        }
    }

    /**
     * 清理一行临时输出。
     */
    private static final class ThinkingIndicator {
        private static final String[] FRAMES = new String[] {
                "正在思考   ",
                "正在思考.  ",
                "正在思考.. ",
                "正在思考..."
        };

        private final Terminal terminal;
        private final AtomicBoolean active = new AtomicBoolean(false);
        private final AtomicBoolean outputStarted = new AtomicBoolean(false);
        private Thread ticker;

        /**
         * 创建思考提示器，负责在助手真正输出前显示动态文案。
         */
        private ThinkingIndicator(Terminal terminal) {
            this.terminal = terminal;
        }

        /**
         * 启动闪烁提示，并在同一行循环刷新。
         */
        private void start() {
            if (!active.compareAndSet(false, true)) {
                return;
            }
            ticker = new Thread(new Runnable() {
                @Override
                public void run() {
                    int index = 0;
                    while (active.get() && !outputStarted.get()) {
                        render(FRAMES[index % FRAMES.length]);
                        index++;
                        try {
                            Thread.sleep(250L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }, "mewcode-thinking-indicator");
            ticker.setDaemon(true);
            ticker.start();
        }

        /**
         * 当首个可见输出出现时停止提示，并恢复助手前缀。
         */
        private void markOutputStarted() {
            if (!outputStarted.compareAndSet(false, true)) {
                return;
            }
            stopTicker();
            synchronized (terminal) {
                terminal.writer().print("\r" + CLEAR_LINE + assistantLeadText());
                terminal.writer().flush();
            }
        }

        /**
         * 在流程结束但还没有正式输出时停止提示，避免残留闪烁文案。
         */
        private void stop() {
            stopTicker();
            if (outputStarted.get()) {
                return;
            }
            synchronized (terminal) {
                terminal.writer().print("\r" + CLEAR_LINE + assistantLeadText());
                terminal.writer().flush();
            }
        }

        /**
         * 停止后台刷新线程。
         */
        private void stopTicker() {
            active.set(false);
            if (ticker != null) {
                ticker.interrupt();
                try {
                    ticker.join(500L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * 使用同一行重绘当前思考帧，避免刷屏。
         */
        private void render(String frame) {
            synchronized (terminal) {
                terminal.writer().print("\r" + CLEAR_LINE + assistantLeadText() + DIM + frame + RESET);
                terminal.writer().flush();
            }
        }
    }

    private static void clearTransientLine(Terminal terminal) {
        terminal.writer().print("\r" + CLEAR_LINE);
        terminal.writer().flush();
    }

    /**
     * 生成光标上移控制序列。
     */
    private static String cursorUp(int lines) {
        if (lines <= 0) {
            return "";
        }
        return "\u001B[" + lines + "A";
    }

    /**
     * 打印启动头部和当前配置。
     */
    private void printHeader(Terminal terminal, LlmProvider provider, Mode mode) {
        terminal.writer().println();
        terminal.writer().println(CYAN + " /\\_/\\\\  " + RESET + BOLD + appName + RESET + GRAY + "  terminal coding agent" + RESET);
        terminal.writer().println(CYAN + "( o.o ) " + RESET + GRAY + "chat 启动成功" + RESET);
        terminal.writer().println(CYAN + " > ^ <  " + RESET + GRAY + "终端纯对话模式" + RESET);
        terminal.writer().println();
        terminal.writer().println(BOLD + appName + RESET + GRAY + "  terminal coding agent" + RESET);
        terminal.writer().println(GRAY + "----------------------------------------------------------------" + RESET);
        terminal.writer().println("  模式   " + modeBadge(mode) + " " + modeDescription(mode));
        terminal.writer().println("  模型   " + safeText(llmConfig.model()));
        terminal.writer().println("  项目   " + safeText(String.valueOf(projectRoot)));
        terminal.writer().println("  协议   " + safeText(llmConfig.protocol()) + GRAY + "  |  提供商   "
                + safeText(provider.name()) + RESET);
        terminal.writer().println("  配置   " + safeText(String.valueOf(configPath)));
        terminal.writer().println(GRAY + "----------------------------------------------------------------" + RESET);
        terminal.writer().println(GRAY + "  MCP   " + safeText(mcpStatus == null ? "" : mcpStatus.summary()) + RESET);
        terminal.writer().println(GRAY + "----------------------------------------------------------------" + RESET);
        terminal.writer().println(GRAY
                + "  /plan 进入规划  /do 执行规划  /resume 恢复会话  /clear 清空上下文  /exit 退出  Shift+Tab / Alt+M 切换模式"
                + RESET);
        terminal.writer().println();
    }

    /**
     * 将工具名映射为中文。
     */
    private static String toolLabel(String toolName) {
        if ("read_file".equals(toolName)) {
            return "读取文件";
        }
        if ("write_file".equals(toolName)) {
            return "写入文件";
        }
        if ("edit_file".equals(toolName)) {
            return "编辑文件";
        }
        if ("glob".equals(toolName)) {
            return "查找文件";
        }
        if ("grep".equals(toolName)) {
            return "搜索内容";
        }
        if ("bash".equals(toolName)) {
            return "执行命令";
        }
        return toolName;
    }

    /**
     * 对终端文本做安全清洗。
     */
    private static String safeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ").trim();
    }

    /**
     * 将审批阶段读到的按键字节流翻译成统一动作。
     */
    static String decodeApprovalAction(int first, ApprovalByteReader reader) throws Exception {
        if (first < 0) {
            return null;
        }
        if (first == 'k' || first == 'K') {
            return "UP";
        }
        if (first == 'j' || first == 'J') {
            return "DOWN";
        }
        if (first == '1') {
            return "ALLOW_ONCE";
        }
        if (first == '2') {
            return "ALLOW_FOREVER";
        }
        if (first == '3') {
            return "DENY_ONCE";
        }
        if (first == '\r' || first == '\n') {
            return "SUBMIT";
        }

        if (first == 0 || first == 224) {
            int second = reader.read(25L);
            if (second == 72) {
                return "UP";
            }
            if (second == 80) {
                return "DOWN";
            }
            return null;
        }

        if (first == 27) {
            int second = reader.read(25L);
            if (second == '[' || second == 'O') {
                int third = reader.read(25L);
                if (third == 'A') {
                    return "UP";
                }
                if (third == 'B') {
                    return "DOWN";
                }
                return null;
            }
            if (second < 0) {
                return "CANCEL";
            }
            return null;
        }
        return null;
    }

    /**
     * 在限定时间内读取下一个输入字节。
     */
    static int readInputByteWithTimeout(InputStream in, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() <= deadline) {
            if (in.available() > 0) {
                return in.read();
            }
            Thread.sleep(5L);
        }
        return -1;
    }

    /**
     * 将时间转换为“多久之前”形式。
     */
    private String formatAgo(Instant modifiedAt) {
        Duration duration = Duration.between(modifiedAt, Instant.now());
        if (duration.toMinutes() < 60) {
            return Math.max(0L, duration.toMinutes()) + " 分钟前";
        }
        if (duration.toHours() < 24) {
            return duration.toHours() + " 小时前";
        }
        return duration.toDays() + " 天前";
    }

    /**
     * 保存当前模式状态，供主循环和快捷键共享。
     */
    private static final class ModeState {
        private Mode current;
        private boolean readingInput;

        private ModeState(Mode current) {
            this.current = current;
        }

        private Mode current() {
            return current;
        }

        private void set(Mode current) {
            this.current = current;
        }

        private void beginInput() {
            this.readingInput = true;
        }

        private void endInput() {
            this.readingInput = false;
        }

        private boolean isReadingInput() {
            return readingInput;
        }
    }

    /**
     * 用于中断当前 readLine，让主循环按新模式重建 prompt。
     */
    private static final class ModeSwitchException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
