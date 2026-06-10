package dev.mewcode.agent.ui;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.Role;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.file.Path;
import java.util.List;

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

    public ChatConsole(String appName, Path configPath, LlmConfig llmConfig) {
        this.appName = appName;
        this.configPath = configPath;
        this.llmConfig = llmConfig;
    }

    /**
     * 启动终端聊天循环，负责读取用户输入、调用 Provider，并打印流式回复。
     */
    public void run(List<ChatMessage> messages, LlmProvider provider) throws Exception {
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
                    messages.clear();
                    terminal.writer().println("对话上下文已重置.");
                    terminal.writer().flush();
                    continue;
                }

                messages.add(new ChatMessage(Role.USER, input));
                terminal.writer().print("MewCode: ");
                terminal.writer().flush();

                String answer = provider.streamChat(messages, text -> {
                    terminal.writer().print(text);
                    terminal.writer().flush();
                });

                terminal.writer().println();
                terminal.writer().flush();
                messages.add(new ChatMessage(Role.ASSISTANT, answer));
            }
        }
    }

    /**
     * 打印启动页：彩色猫咪头、产品名、Provider 和当前模型信息。
     */
    private void printHeader(Terminal terminal, LlmProvider provider) {
        terminal.writer().println();
        terminal.writer().println(CYAN + " /\\_/\\  " + RESET + BOLD + appName + RESET + " " + DIM + "Coding Agent" + RESET);
        terminal.writer().println(MAGENTA + "( o.o ) " + RESET + "chat 启动成功");
        terminal.writer().println(YELLOW + " > ^ <  " + RESET + DIM + "终端纯对话模式" + RESET);
        terminal.writer().println();
        terminal.writer().printf("%s服务提供商:%s %s%n", GREEN, RESET, provider.name());
        terminal.writer().printf("%s协议:%s %s%n", GREEN, RESET, llmConfig.protocol());
        terminal.writer().printf("%s模型:%s %s%n", GREEN, RESET, llmConfig.model());
        terminal.writer().printf("%sBase URL:%s %s%n", GREEN, RESET, llmConfig.baseUrl());
        terminal.writer().printf("%s配置文件:%s %s%n", GREEN, RESET, configPath);
        terminal.writer().println();
        terminal.writer().println("执行 /exit 或 /quit 退出对话, /clear 重置对话上下文信息.");
        terminal.writer().println();
    }
}
