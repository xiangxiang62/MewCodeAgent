package dev.mewcode.agent.ui;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.prompt.Reminder;
import dev.mewcode.agent.runtime.ToolAgent;
import dev.mewcode.agent.runtime.ToolDisplay;
import dev.mewcode.agent.tool.Registry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 终端交互界面，负责输入、输出和模式切换。
 */
public final class ChatConsole {
    private static final String RESET = "\u001B[0m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";
    private static final String CYAN = "\u001B[36m";
    private static final String MAGENTA = "\u001B[35m";
    private static final String YELLOW = "\u001B[33m";
    private static final String GREEN = "\u001B[32m";

    private final String appName;
    private final Path configPath;
    private final LlmConfig llmConfig;

    /**
     * 创建终端会话对象。
     */
    public ChatConsole(String appName, Path configPath, LlmConfig llmConfig) {
        this.appName = appName;
        this.configPath = configPath;
        this.llmConfig = llmConfig;
    }

    /**
     * 启动交互循环，持续读取用户输入并驱动代理执行。
     */
    public void run(List<ChatMessage> messages, LlmProvider provider, Registry registry) throws Exception {
        ToolAgent.Mode mode = ToolAgent.Mode.NORMAL;
        try (Terminal terminal = TerminalBuilder.builder()
                .name(appName)
                .system(true)
                .build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .appName(appName)
                    .build();

            printHeader(terminal, provider);
            terminal.writer().flush();

            while (true) {
                String input;
                try {
                    input = reader.readLine("> ");
                } catch (UserInterruptException | EndOfFileException e) {
                    terminal.writer().println();
                    return;
                }

                if (input == null || input.trim().isEmpty()) {
                    continue;
                }

                String trimmed = input.trim();
                if ("/exit".equalsIgnoreCase(trimmed) || "/quit".equalsIgnoreCase(trimmed)) {
                    return;
                }
                if ("/clear".equalsIgnoreCase(trimmed)) {
                    mode = ToolAgent.Mode.NORMAL;
                    messages.clear();
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.buildSystemPrompt()));
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
                    terminal.writer().println("对话上下文已重置。");
                    terminal.writer().flush();
                    continue;
                }
                if ("/plan".equalsIgnoreCase(trimmed)) {
                    mode = ToolAgent.Mode.PLAN;
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_PLAN));
                    terminal.writer().println("已进入计划模式：仅允许只读工具，先调研并输出计划。");
                    terminal.writer().flush();
                    continue;
                }
                if ("/do".equalsIgnoreCase(trimmed)) {
                    mode = ToolAgent.Mode.NORMAL;
                    messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
                    messages.add(new ChatMessage(Role.USER, Reminder.EXECUTE_DIRECTIVE));
                    terminal.writer().print("MewCode: ");
                    terminal.writer().flush();
                    runAgent(messages, provider, registry, terminal, mode);
                    terminal.writer().println();
                    terminal.writer().flush();
                    continue;
                }

                messages.add(new ChatMessage(Role.USER, input));
                terminal.writer().print("MewCode: ");
                terminal.writer().flush();
                runAgent(messages, provider, registry, terminal, mode);
                terminal.writer().println();
                terminal.writer().flush();
            }
        }
    }

    /**
     * 调用代理执行当前一轮对话，并把文本增量直接写回终端。
     */
    private void runAgent(List<ChatMessage> messages, LlmProvider provider, Registry registry,
            Terminal terminal, ToolAgent.Mode mode) {
        ToolAgent agent = new ToolAgent(provider, registry);
        try {
            agent.run(messages, text -> {
                terminal.writer().print(text);
                terminal.writer().flush();
            }, new ConsoleToolDisplay(terminal), mode);
        } catch (Exception e) {
            terminal.writer().println();
            terminal.writer().println(YELLOW + "请求失败: " + e.getMessage() + RESET);
            terminal.writer().flush();
        }
    }

    /**
     * 控制工具调用在终端中的显示格式。
     */
    private static final class ConsoleToolDisplay implements ToolDisplay {
        private static final int SUMMARY_LINES = 8;
        private static final Map<String, String> TOOL_LABELS = createToolLabels();

        private final Terminal terminal;
        private final Map<String, RunningTool> runningTools = new ConcurrentHashMap<String, RunningTool>();

        /**
         * 创建工具显示器。
         */
        private ConsoleToolDisplay(Terminal terminal) {
            this.terminal = terminal;
        }

        /**
         * 打印工具开始执行的提示，并启动耗时计时器。
         */
        @Override
        public void onToolStart(String name, String args) {
            String key = toolKey(name, args);
            RunningTool runningTool = new RunningTool();
            runningTools.put(key, runningTool);

            terminal.writer().println();
            terminal.writer().println(CYAN + "调用工具：[" + toolLabel(name) + "]" + RESET);
            terminal.writer().println(CYAN + "● " + name + "(" + args + ")" + RESET);
            terminal.writer().println(DIM + "执行中：0秒" + RESET);
            terminal.writer().flush();

            runningTool.start(terminal);
        }

        /**
         * 打印工具执行结果摘要和用时信息。
         */
        @Override
        public void onToolEnd(String name, String args, String result, boolean error) {
            String key = toolKey(name, args);
            RunningTool runningTool = runningTools.remove(key);
            long elapsedSeconds = runningTool == null ? 0L : runningTool.finish();

            terminal.writer().println((error ? YELLOW : GREEN) + "执行完成，用时：" + elapsedSeconds + "秒" + RESET);

            String color = error ? YELLOW : DIM;
            String[] lines = result == null ? new String[0] : result.split("\\R", -1);
            int limit = Math.min(lines.length, SUMMARY_LINES);
            for (int i = 0; i < limit; i++) {
                terminal.writer().println(color + "  ↳ " + lines[i] + RESET);
            }
            if (lines.length > SUMMARY_LINES) {
                terminal.writer().println(color + "  ↳ [truncated]" + RESET);
            }
            terminal.writer().print("MewCode: ");
            terminal.writer().flush();
        }

        /**
         * 生成工具运行实例的唯一键。
         */
        private String toolKey(String name, String args) {
            return name + "\n" + args;
        }

        /**
         * 返回工具的中文显示名。
         */
        private String toolLabel(String name) {
            String label = TOOL_LABELS.get(name);
            return label == null ? name : label;
        }

        /**
         * 创建内置工具中文标签映射。
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
     * 用于在工具执行期间持续打印耗时信息。
     */
    private static final class RunningTool {
        private final long startedAt = System.currentTimeMillis();
        private final AtomicBoolean active = new AtomicBoolean(true);
        private Thread ticker;

        /**
         * 启动后台计时线程。
         */
        private void start(final Terminal terminal) {
            ticker = new Thread(new Runnable() {
                @Override
                public void run() {
                    long lastPrinted = 0L;
                    while (active.get()) {
                        long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
                        if (elapsedSeconds > lastPrinted) {
                            terminal.writer().println(DIM + "执行中：" + elapsedSeconds + "秒" + RESET);
                            terminal.writer().flush();
                            lastPrinted = elapsedSeconds;
                        }
                        try {
                            Thread.sleep(200L);
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
         * 停止计时线程并返回总耗时秒数。
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
     * 打印启动横幅和当前配置信息。
     */
    private void printHeader(Terminal terminal, LlmProvider provider) {
        terminal.writer().println();
        terminal.writer().println(CYAN + " /\\_/\\\\  " + RESET + BOLD + appName + RESET + " " + DIM
                + "Coding Agent" + RESET);
        terminal.writer().println(MAGENTA + "( o.o ) " + RESET + "chat 启动成功");
        terminal.writer().println(YELLOW + " > ^ <  " + RESET + DIM + "终端纯对话模式" + RESET);
        terminal.writer().println();
        terminal.writer().printf("%s服务提供商:%s %s%n", GREEN, RESET, provider.name());
        terminal.writer().printf("%s协议:%s %s%n", GREEN, RESET, llmConfig.protocol());
        terminal.writer().printf("%s模型:%s %s%n", GREEN, RESET, llmConfig.model());
        terminal.writer().printf("%sBase URL:%s %s%n", GREEN, RESET, llmConfig.baseUrl());
        terminal.writer().printf("%s配置文件:%s %s%n", GREEN, RESET, configPath);
        terminal.writer().println();
        terminal.writer().println("执行 /exit 或 /quit 退出对话，/clear 重置上下文，/plan 进入计划模式，/do 按计划执行");
        terminal.writer().println();
    }
}
