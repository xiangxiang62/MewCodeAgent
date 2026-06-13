package dev.mewcode.agent;

import dev.mewcode.agent.config.AppConfig;
import dev.mewcode.agent.config.ConfigLoader;
import dev.mewcode.agent.instructions.Loader;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmProviderFactory;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.memory.Manager;
import dev.mewcode.agent.mcp.McpConfig;
import dev.mewcode.agent.mcp.McpManager;
import dev.mewcode.agent.mcp.McpStatus;
import dev.mewcode.agent.permission.PermissionEngine;
import dev.mewcode.agent.prompt.Prompt;
import dev.mewcode.agent.session.SessionCleaner;
import dev.mewcode.agent.session.Writer;
import dev.mewcode.agent.tool.Registry;
import dev.mewcode.agent.tool.Tool;
import dev.mewcode.agent.ui.ChatConsole;
import dev.mewcode.agent.compact.state.SessionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MewCodeApp {
    private MewCodeApp() {
    }

    /**
     * 程序主入口：解析配置、创建模型提供方，并启动终端对话循环。
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
        Path projectRoot = resolveProjectRoot(configPath);
        Loader instructionLoader = new Loader(projectRoot);
        String instructionText = instructionLoader.load();
        Manager memoryManager = new Manager(projectRoot.resolve(".mewcode").resolve("memory"),
                Paths.get(System.getProperty("user.home")).resolve(".mewcode").resolve("memory"),
                provider,
                config.llm().model());
        String memoryText = memoryManager.loadIndex();
        SessionContext sessionContext = SessionContext.create(projectRoot);
        Writer sessionWriter = Writer.create(sessionContext.sessionDir());
        SessionCleaner.cleanExpired(projectRoot.resolve(".mewcode").resolve("sessions"), Duration.ofDays(30));

        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(Role.SYSTEM, Prompt.buildSystemPrompt(instructionText, memoryText)));
        messages.add(new ChatMessage(Role.SYSTEM, Prompt.MODE_STATUS_NORMAL));
        sessionWriter.append(messages.get(0), config.llm().model(), true);
        sessionWriter.append(messages.get(1), config.llm().model(), true);
        Registry registry = Registry.defaultRegistry();
        McpConfig mcpConfig = dev.mewcode.agent.mcp.ConfigLoader.loadConfig(projectRoot);
        PermissionEngine permissionEngine = PermissionEngine.create(projectRoot);
        McpStatus mcpStatus = createInitialMcpStatus(mcpConfig);

        ExecutorService mcpLoader = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mcp-loader");
            thread.setDaemon(true);
            return thread;
        });
        Future<McpManager> mcpFuture = McpManager.startAsync(mcpConfig, "0.1.0-SNAPSHOT", mcpLoader);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (mcpFuture.isDone()) {
                    mcpFuture.get().close();
                }
            } catch (Exception ignored) {
                // 退出阶段只做兜底清理，不影响主流程退出。
            } finally {
                mcpLoader.shutdownNow();
            }
        }, "mcp-shutdown"));

        ChatConsole console = new ChatConsole("MewCode", configPath, config.llm(), permissionEngine,
                projectRoot, mcpStatus, instructionText, memoryManager, memoryText, sessionContext, sessionWriter);
        startBackgroundMcpAttach(registry, mcpFuture, mcpLoader, mcpStatus);
        console.run(messages, provider, registry);
    }

    /**
     * 终端启动后再在后台补挂 MCP，尽量缩短首屏等待时间。
     */
    private static void startBackgroundMcpAttach(Registry registry, Future<McpManager> mcpFuture,
            ExecutorService mcpLoader, McpStatus mcpStatus) {
        mcpLoader.submit(() -> {
            try {
                McpManager manager = mcpFuture.get();
                for (Tool tool : manager.tools()) {
                    registry.register(tool);
                }
                mcpStatus.update(buildConnectedSummary(manager.serverCount(), manager.tools().size()));
            } catch (Exception e) {
                mcpStatus.update("MCP 后台加载失败，可继续使用内置工具");
                System.err.println("[mcp] warn: background attach failed: " + e.getMessage());
            }
        });
    }

    private static McpStatus createInitialMcpStatus(McpConfig mcpConfig) {
        if (mcpConfig == null || mcpConfig.servers().isEmpty()) {
            return new McpStatus("未配置 MCP server");
        }
        return new McpStatus("正在后台连接 MCP，可先使用内置工具");
    }

    private static String buildConnectedSummary(int serverCount, int toolCount) {
        if (serverCount <= 0) {
            return "MCP 检查完成，未连接到可用 server";
        }
        return "已连接 " + serverCount + " 个 MCP server，已注册 " + toolCount + " 个工具";
    }

    /**
     * 解析配置文件路径。
     * 优先级依次为：命令行参数、环境变量、常见默认位置。
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
     * 根据配置文件位置推断项目根目录，优先让项目级配置跟随实际项目而不是启动时 cwd。
     */
    private static Path resolveProjectRoot(Path configPath) {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if (configPath == null) {
            return cwd;
        }

        Path absoluteConfigPath = configPath.toAbsolutePath().normalize();
        Path configParent = absoluteConfigPath.getParent();
        if (configParent == null) {
            return cwd;
        }
        if (Files.exists(configParent.resolve(".mewcode.yaml"))) {
            return configParent;
        }
        return cwd;
    }

    /**
     * 在找不到配置时输出启动说明，帮助用户快速定位配置来源。
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
