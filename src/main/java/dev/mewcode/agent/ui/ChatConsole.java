package dev.mewcode.agent.ui;

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
    private final String appName;
    private final Path configPath;

    public ChatConsole(String appName, Path configPath) {
        this.appName = appName;
        this.configPath = configPath;
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

            terminal.writer().printf("%s chat 启动成功. 服务提供商: %s. 配置文件: %s%n", appName, provider.name(), configPath);
            terminal.writer().println("执行 /exit 或 /quit 退出对话, /clear 重置对话上下文信息.");
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
}
