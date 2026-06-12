package dev.mewcode.agent.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.tool.Result;
import dev.mewcode.agent.tool.Tool;
import dev.mewcode.agent.tool.ToolContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 把 MCP 远程工具适配为 MewCode 的本地工具抽象。
 */
public final class McpTool implements Tool {
    private static final Pattern VALID_NAME = Pattern.compile("^[A-Za-z0-9_-]+$");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<String, Boolean> NON_TEXT_WARNED = new ConcurrentHashMap<String, Boolean>();

    private final String fullName;
    private final String remoteName;
    private final String description;
    private final Map<String, Object> parameters;
    private final boolean readOnly;
    private final CallerSession session;

    /**
     * 最小化远程调用接口，便于测试注入。
     */
    interface CallerSession {
        /**
         * 调用远程工具。
         */
        CallToolResult callTool(String name, Map<String, Object> arguments) throws Exception;
    }

    private McpTool(String fullName, String remoteName, String description, Map<String, Object> parameters,
            boolean readOnly, CallerSession session) {
        this.fullName = fullName;
        this.remoteName = remoteName;
        this.description = description;
        this.parameters = parameters;
        this.readOnly = readOnly;
        this.session = session;
    }

    /**
     * 把 SDK 工具定义适配为本地 Tool。
     */
    public static Optional<McpTool> adaptTool(String serverName, McpSchema.Tool tool, CallerSession session) {
        String fullName = "mcp__" + serverName + "__" + tool.name();
        if (!VALID_NAME.matcher(fullName).matches()) {
            System.err.println("[mcp] warn: skip tool " + fullName + ": name contains illegal characters");
            return Optional.empty();
        }

        String description = tool.description();
        if (description == null || description.trim().isEmpty()) {
            description = "来自 MCP server " + serverName + " 的工具 " + tool.name();
        }

        Map<String, Object> schema = schemaToMap(tool.inputSchema());
        boolean readOnly = false;
        return Optional.of(new McpTool(fullName, tool.name(), description, schema, readOnly, session));
    }

    private static Map<String, Object> schemaToMap(McpSchema.JsonSchema schema) {
        if (schema == null) {
            return defaultSchema();
        }
        Map<String, Object> map = JSON.convertValue(schema, new TypeReference<Map<String, Object>>() {
        });
        if (map == null || map.isEmpty()) {
            return defaultSchema();
        }
        return map;
    }

    private static Map<String, Object> defaultSchema() {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        return schema;
    }

    @Override
    public String name() {
        return fullName;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public Map<String, Object> parameters() {
        return parameters;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public Result execute(ToolContext context, String inputJson) {
        Map<String, Object> arguments;
        try {
            arguments = JSON.readValue(inputJson == null || inputJson.trim().isEmpty() ? "{}" : inputJson,
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return Result.error("参数解析失败: " + e.getMessage());
        }

        try {
            CallToolResult result = session.callTool(remoteName,
                    arguments == null ? Collections.<String, Object>emptyMap() : arguments);
            return toResult(result);
        } catch (Exception e) {
            return Result.error("MCP 工具调用失败: " + e.getMessage());
        }
    }

    private Result toResult(CallToolResult result) {
        if (result == null) {
            return Result.error("MCP 工具调用失败: 返回结果为空");
        }
        StringBuilder content = new StringBuilder();
        if (result.content() != null) {
            for (Content block : result.content()) {
                if (block instanceof TextContent) {
                    if (content.length() > 0) {
                        content.append('\n');
                    }
                    content.append(((TextContent) block).text());
                } else if (NON_TEXT_WARNED.putIfAbsent(fullName, Boolean.TRUE) == null) {
                    System.err.println(
                            "[mcp] warn: tool " + fullName + " returned non-text content blocks (dropped)");
                }
            }
        }
        return new Result(content.toString(), Boolean.TRUE.equals(result.isError()));
    }

    /**
     * 生产环境里的 SDK 会话调用适配器。
     */
    static final class AsyncCallerSession implements CallerSession {
        private final io.modelcontextprotocol.client.McpAsyncClient client;

        AsyncCallerSession(io.modelcontextprotocol.client.McpAsyncClient client) {
            this.client = client;
        }

        @Override
        public CallToolResult callTool(String name, Map<String, Object> arguments) {
            return client.callTool(new CallToolRequest(name, arguments)).block(java.time.Duration.ofSeconds(30L));
        }
    }
}
