package dev.mewcode.agent.mcp;

import dev.mewcode.agent.mcp.McpConfig.ServerConfig;
import dev.mewcode.agent.tool.Tool;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.Implementation;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 负责 MCP 连接建立、工具发现和统一关闭。
 */
public final class McpManager implements AutoCloseable {
    static volatile long connectTimeoutSeconds = 30L;
    static volatile long closeTimeoutSeconds = 5L;

    private final Object lock = new Object();
    private final List<Session> sessions = new ArrayList<Session>();
    private final List<Tool> tools = new ArrayList<Tool>();

    /**
     * 启动全部 MCP server 并收集工具。
     */
    public static McpManager start(McpConfig config, String version) {
        McpManager manager = new McpManager();
        Map<String, ServerConfig> servers = config == null ? Collections.<String, ServerConfig>emptyMap() : config.servers();
        if (servers.isEmpty()) {
            return manager;
        }

        CountDownLatch latch = new CountDownLatch(servers.size());
        ExecutorService executor = Executors.newCachedThreadPool();
        for (Map.Entry<String, ServerConfig> entry : servers.entrySet()) {
            final String serverName = entry.getKey();
            final ServerConfig serverConfig = entry.getValue();
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        connectOne(manager, serverName, serverConfig, version);
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }

        synchronized (manager.lock) {
            Collections.sort(manager.tools, new Comparator<Tool>() {
                @Override
                public int compare(Tool left, Tool right) {
                    return left.name().compareTo(right.name());
                }
            });
        }
        return manager;
    }

    /**
     * 在后台线程中连接 MCP server，避免阻塞主启动流程。
     */
    public static Future<McpManager> startAsync(McpConfig config, String version, ExecutorService executor) {
        return executor.submit(() -> start(config, version));
    }

    private static void connectOne(McpManager manager, String serverName, ServerConfig serverConfig, String version) {
        try {
            McpClientTransport transport = createTransport(serverConfig);
            McpAsyncClient client = McpClient.async(transport)
                    .requestTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .initializationTimeout(Duration.ofSeconds(connectTimeoutSeconds))
                    .clientInfo(new Implementation("mewcode", version))
                    .build();

            client.initialize().block(Duration.ofSeconds(connectTimeoutSeconds));
            ListToolsResult listToolsResult = client.listTools().block(Duration.ofSeconds(connectTimeoutSeconds));

            List<Tool> adaptedTools = new ArrayList<Tool>();
            if (listToolsResult != null && listToolsResult.tools() != null) {
                for (McpSchema.Tool tool : listToolsResult.tools()) {
                    Optional<McpTool> adapted = McpTool.adaptTool(serverName, tool, new McpTool.AsyncCallerSession(client));
                    if (adapted.isPresent()) {
                        adaptedTools.add(adapted.get());
                    }
                }
            }

            synchronized (manager.lock) {
                manager.sessions.add(new Session(serverName, client));
                manager.tools.addAll(adaptedTools);
            }
        } catch (Exception e) {
            System.err.println("[mcp] warn: connect server " + serverName + " failed: " + e.getMessage());
        }
    }

    private static McpClientTransport createTransport(ServerConfig config) {
        if ("stdio".equals(config.type())) {
            ServerParameters parameters = new ServerParameters.Builder(config.command())
                    .args(config.args())
                    .env(mergeOsEnv(config.env()))
                    .build();
            StdioClientTransport transport = new StdioClientTransport(parameters);
            transport.setStdErrorHandler(line -> System.err.println(line));
            return transport;
        }

        HttpClientSseClientTransport.Builder builder = HttpClientSseClientTransport.builder(config.url())
                .clientBuilder(HttpClient.newBuilder());
        if (!config.headers().isEmpty()) {
            builder.customizeRequest(requestBuilder -> {
                for (Map.Entry<String, String> header : config.headers().entrySet()) {
                    requestBuilder.header(header.getKey(), header.getValue());
                }
            });
        }
        return builder.build();
    }

    static Map<String, String> mergeOsEnv(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<String, String>(System.getenv());
        if (extra != null) {
            merged.putAll(extra);
        }
        return merged;
    }

    /**
     * 返回全部已注册的 MCP 工具。
     */
    public List<Tool> tools() {
        synchronized (lock) {
            return Collections.unmodifiableList(new ArrayList<Tool>(tools));
        }
    }

    /**
     * 返回已成功连接的 MCP server 数量。
     */
    public int serverCount() {
        synchronized (lock) {
            return sessions.size();
        }
    }

    /**
     * 关闭全部 MCP 会话。
     */
    @Override
    public void close() {
        List<Session> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<Session>(sessions);
        }
        if (snapshot.isEmpty()) {
            return;
        }

        CountDownLatch latch = new CountDownLatch(snapshot.size());
        ExecutorService executor = Executors.newCachedThreadPool();
        for (Session session : snapshot) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        session.client().closeGracefully().block(Duration.ofSeconds(closeTimeoutSeconds));
                    } catch (Exception ignored) {
                        // 关闭阶段只兜底，不向外抛异常。
                    } finally {
                        latch.countDown();
                    }
                }
            });
        }

        try {
            latch.await(closeTimeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 已建立连接的 MCP 会话记录。
     */
    static final class Session {
        private final String name;
        private final McpAsyncClient client;

        Session(String name, McpAsyncClient client) {
            this.name = name;
            this.client = client;
        }

        String name() {
            return name;
        }

        McpAsyncClient client() {
            return client;
        }
    }
}
