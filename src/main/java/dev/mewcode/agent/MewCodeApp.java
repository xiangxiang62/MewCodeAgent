package dev.mewcode.agent;

import dev.mewcode.agent.config.AppConfig;
import dev.mewcode.agent.config.ConfigLoader;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmProviderFactory;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.tool.Registry;
import dev.mewcode.agent.ui.ChatConsole;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class MewCodeApp {
    private MewCodeApp() {
    }

    /**
     * 程序主入口：解析配置、创建 LLM Provider，然后启动终端聊天循环。
     */
    public static void main(String[] args) throws Exception {
        Path configPath = resolveConfigPath(args);
        if (configPath == null) {
            printConfigHelp();
            System.exit(2);
            return;
        }

        AppConfig config;
        LlmProvider provider;
        try {
            config = ConfigLoader.load(configPath);
            provider = LlmProviderFactory.create(config.llm());
        } catch (IllegalArgumentException e) {
            System.err.println("MewCode 启动失败: " + e.getMessage());
            System.err.println();
            System.err.println("请检查配置文件，或参考 config.example.yaml 创建配置。");
            System.exit(2);
            return;
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(Role.SYSTEM, Prompt.SYSTEM_PROMPT));
        messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
        Registry registry = Registry.defaultRegistry();

        ChatConsole console = new ChatConsole("MewCode", configPath, config.llm());
        console.run(messages, provider, registry);
    }

    /**
     * 解析配置文件路径。优先使用显式参数，其次环境变量，最后查找常见默认位置。
     */
    private static Path resolveConfigPath(String[] args) {
        if (args.length > 0) {
            return Paths.get(args[0]);
        }

        String envPath = System.getenv("MEWCODE_CONFIG");
        if (envPath != null && !envPath.trim().isEmpty()) {
            return Paths.get(envPath);
        }

        List<Path> candidates = Arrays.asList(
                Paths.get("config.yaml"),
                Paths.get("config.yml"),
                Paths.get(System.getProperty("user.home"), ".mewcode", "config.yaml"),
                Paths.get(System.getProperty("user.home"), ".mewcode", "config.yml")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 打印配置文件缺失时的引导信息，避免用户只看到异常栈。
     */
    private static void printConfigHelp() {
        System.err.println("未找到 MewCode 配置文件。");
        System.err.println();
        System.err.println("请使用以下任意一种方式提供配置:");
        System.err.println("  1. 启动时显式传入路径: java -jar target/mewcode-agent-0.1.0-SNAPSHOT.jar config.yaml");
        System.err.println("  2. 设置 MEWCODE_CONFIG 环境变量指向 YAML 配置文件");
        System.err.println("  3. 在当前项目目录创建 ./config.yaml");
        System.err.println("  4. 在用户目录创建 ~/.mewcode/config.yaml");
        System.err.println();
        System.err.println("可以参考 config.example.yaml 创建配置。");
    }
}
