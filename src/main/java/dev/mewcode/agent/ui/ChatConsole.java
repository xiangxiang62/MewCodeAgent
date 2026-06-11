package dev.mewcode.agent.ui;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.permission.Mode;
import dev.mewcode.agent.permission.Outcome;
import dev.mewcode.agent.permission.PermissionEngine;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.prompt.Reminder;
import dev.mewcode.agent.runtime.ApprovalHandler;
import dev.mewcode.agent.runtime.ToolAgent;
import dev.mewcode.agent.runtime.ToolDisplay;
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

    /**
     * 审批按键后续字节读取器，便于在单元测试中模拟不同终端键序列。
     */
    interface ApprovalByteReader {
        int read(long timeoutMillis) throws Exception;
    }

    /**
     * 创建终端会话对象。
     */
    public ChatConsole(String appName, Path configPath, LlmConfig llmConfig, PermissionEngine permissionEngine) {
        this.appName = appName;
        this.configPath = configPath;
        this.llmConfig = llmConfig;
        this.permissionEngine = permissionEngine;
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
                    printNotice(terminal, "模式已切换到 " + modeBadge(modeState.current()) + " "
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
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.buildSystemPrompt()));
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
                    printNotice(terminal, "已清空上下文。", modeState.current());
                    continue;
                }
                if ("/plan".equalsIgnoreCase(trimmed)) {
                    modeState.set(Mode.PLAN);
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_PLAN));
                    printNotice(terminal, "已切换到 " + modeBadge(modeState.current()) + " "
                            + modeDescription(modeState.current()) + "。", modeState.current());
                    continue;
                }
                if ("/do".equalsIgnoreCase(trimmed)) {
                    modeState.set(Mode.DEFAULT);
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
                    messages.add(new ChatMessage(Role.USER, Reminder.EXECUTE_DIRECTIVE));
                    printAssistantLead(terminal);
                    runAgent(messages, provider, registry, terminal, modeState.current(), modeState);
                    terminal.writer().println();
                    terminal.writer().flush();
                    continue;
                }

                messages.add(new ChatMessage(Role.USER, input));
                printAssistantLead(terminal);
                runAgent(messages, provider, registry, terminal, modeState.current(), modeState);
                terminal.writer().println();
                terminal.writer().flush();
            }
        }
    }

    /**
     * 尽量让特殊按键序列透传到终端。
     */
    private void maybeEnableEnhancedKeyboard(Terminal terminal) {
        terminal.puts(InfoCmp.Capability.keypad_xmit);
        terminal.flush();
    }

    /**
     * 安装模式切换快捷键，支持 Shift+Tab，也提供 Alt+M 兜底。
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
                reader.printAbove(GRAY + "模式已切换到 " + modeBadge(modeState.current()) + " "
                        + modeDescription(modeState.current()) + "。" + RESET);
                refreshPrompt(reader);
                return true;
            }
        };
        reader.getWidgets().put("cycle-mode", cycleModeWidget);
        bindAllKeyMaps(reader, new Reference("cycle-mode"), SHIFT_TAB, ALT_M, ALT_SHIFT_M);
    }

    /**
     * 强制刷新当前输入行，避免模式切换后 prompt 停留在旧状态。
     */
    private void refreshPrompt(LineReader reader) {
        try {
            reader.callWidget(LineReader.REDRAW_LINE);
        } catch (Exception ignored) {
            // 某些终端不支持时忽略，后续的重显仍能兜底。
        }
        try {
            reader.callWidget(LineReader.REDISPLAY);
        } catch (Exception ignored) {
            // 某些终端不支持时忽略，后续的重显仍能兜底。
        }
    }

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
        ToolAgent agent = new ToolAgent(provider, registry, permissionEngine,
                new ConsoleApprovalHandler(terminal, modeState));
        try {
            agent.run(messages, text -> {
                terminal.writer().print(text);
                terminal.writer().flush();
            }, new ConsoleToolDisplay(terminal), mode);
        } catch (Exception e) {
            terminal.writer().println();
            terminal.writer().println(RED + "请求失败: " + e.getMessage() + RESET);
            terminal.writer().flush();
        }
    }

    private void printNotice(Terminal terminal, String message, Mode mode) {
        terminal.writer().println();
        terminal.writer().println(GRAY + message + RESET);
        terminal.writer().println(DIM + "当前模式: " + shortModeName(mode) + " | " + modeDescription(mode) + RESET);
        terminal.writer().flush();
    }

    private String renderPrompt(Mode mode) {
        return modePrompt(mode) + CYAN + ">" + RESET + " ";
    }

    private void printAssistantLead(Terminal terminal) {
        terminal.writer().print(BOLD + "MewCode" + RESET + GRAY + " > " + RESET);
        terminal.writer().flush();
    }

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
         * 直接解析审批阶段的按键流，兼容 ANSI 序列、Windows 扩展键码和字符键。
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

        private String approvalOption(boolean active, String index, String title, String desc) {
            String prefix = active ? CYAN + "  > " + RESET : "    ";
            String label = active ? BOLD + "[" + index + "] " + title + RESET : "[" + index + "] " + title;
            return prefix + label + GRAY + "  " + desc + RESET;
        }

        private int nextIndex(int selected) {
            return (selected + 1) % 3;
        }

        private int previousIndex(int selected) {
            return (selected + 2) % 3;
        }

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
        private final Map<String, RunningTool> runningTools = new ConcurrentHashMap<String, RunningTool>();

        private ConsoleToolDisplay(Terminal terminal) {
            this.terminal = terminal;
        }

        @Override
        public void onToolStart(String name, String args) {
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

        private static void printAssistantLeadStatic(Terminal terminal) {
            terminal.writer().print(BOLD + "MewCode" + RESET + GRAY + " > " + RESET);
            terminal.writer().flush();
        }

        private String toolKey(String name, String args) {
            return name + "\n" + args;
        }

        private String toolLabel(String name) {
            String label = TOOL_LABELS.get(name);
            return label == null ? name : label;
        }

        private String trimLine(String line) {
            String text = safeText(line);
            return text.length() <= 120 ? text : text.substring(0, 117) + "...";
        }

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
     * 在工具执行期间打印简短的耗时提示。
     */
    private static final class RunningTool {
        private final long startedAt = System.currentTimeMillis();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Thread ticker;

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

    private static void clearTransientLine(Terminal terminal) {
        terminal.writer().print("\r" + CLEAR_LINE);
        terminal.writer().flush();
    }

    private static String cursorUp(int lines) {
        if (lines <= 0) {
            return "";
        }
        return "\u001B[" + lines + "A";
    }

    /**
     * 打印启动头部和当前配置信息。
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
        terminal.writer().println("  协议   " + safeText(llmConfig.protocol()) + GRAY + "  |  提供商   "
                + safeText(provider.name()) + RESET);
        terminal.writer().println("  配置   " + safeText(String.valueOf(configPath)));
        terminal.writer().println(GRAY + "----------------------------------------------------------------" + RESET);
        terminal.writer().println(GRAY
                + "  /plan 进入规划  /do 执行规划  /clear 清空上下文  /exit 退出  Shift+Tab / Alt+M 切换模式"
                + RESET);
        terminal.writer().println();
    }

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

    private static String safeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r", " ").replace("\n", " ").trim();
    }

    /**
     * 将审批阶段读取到的按键字节流翻译成统一动作，兼容 Windows 扩展键和 ANSI 序列。
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

        // Windows 控制台常见扩展键：0/224 + 72(上) / 80(下)
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
     * 在指定超时时间内读取下一个输入字节，避免方向键半包时永久阻塞。
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
     * 持有当前模式，便于快捷键和主循环共享更新。
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
     * 用于中断当前 readLine，让主循环按新模式重新创建 prompt。
     */
    private static final class ModeSwitchException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
