package dev.mewcode.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.llm.http.SseClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OpenAiProvider implements LlmProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean DEBUG_TOOL_CALLS = Boolean.parseBoolean(
            System.getenv().getOrDefault("MEWCODE_DEBUG_TOOL_CALLS", "false"));

    private final LlmConfig config;

    public OpenAiProvider(LlmConfig config) {
        this.config = config;
    }

    /**
     * 返回当前 Provider 的展示名称。
     */
    @Override
    public String name() {
        return "OpenAI";
    }

    /**
     * 调用 OpenAI Chat Completions 流式接口，并把文本增量回调给终端层。
     */
    @Override
    public ChatResponse streamChat(List<ChatMessage> messages, List<ToolDefinition> tools, StreamCallback callback) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("max_tokens", config.effectiveMaxTokens());
        payload.put("stream", true);
        payload.put("messages", toWireMessages(messages));
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", toWireTools(tools));
            payload.put("tool_choice", "auto");
            payload.put("parallel_tool_calls", false);
        }
        debug("OpenAI request payload: " + redactSecrets(JSON.writeValueAsString(payload)));

        HttpURLConnection connection = openConnection(endpoint(), JSON.writeValueAsString(payload));
        ensureSuccess(connection);
        StringBuilder fullText = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<>();
        SseClient.consume(connection.getInputStream(), data -> handleData(data, callback, fullText, toolCalls));
        return new ChatResponse(fullText.toString(), buildToolCalls(toolCalls));
    }

    /**
     * 创建并写入 OpenAI HTTP 请求。这里保持 InputStream 流式读取，避免提前缓存完整响应。
     */
    private HttpURLConnection openConnection(URI endpoint, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.toURL().openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(0);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + config.apiKey());
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "text/event-stream");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(bytes);
        }
        return connection;
    }

    /**
     * 检查 HTTP 状态码，失败时带上请求 ID 便于排查服务端问题。
     */
    private void ensureSuccess(HttpURLConnection connection) throws IOException {
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("OpenAI API request failed with HTTP " + statusCode
                    + ", request id: " + valueOrUnknown(connection.getHeaderField("x-request-id")));
        }
    }

    /**
     * 将空值统一展示为 unknown。
     */
    private String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }

    /**
     * 生成 Chat Completions endpoint，兼容 base_url 和完整 endpoint 两种配置。
     */
    private URI endpoint() {
        String base = config.baseUrl().replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) {
            return URI.create(base);
        }
        return URI.create(base + "/chat/completions");
    }

    /**
     * 将内部消息结构转换为 OpenAI API 接收的 messages 数组。
     */
    private List<Map<String, Object>> toWireMessages(List<ChatMessage> messages) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message.role() == dev.mewcode.agent.llm.Role.TOOL) {
                for (ToolResult result : message.toolResults()) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("role", "tool");
                    item.put("tool_call_id", result.toolCallId());
                    item.put("content", result.content());
                    wire.add(item);
                }
            } else {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("role", message.role().wireName());
                item.put("content", message.content());
                if (!message.toolCalls().isEmpty()) {
                    item.put("tool_calls", toOpenAiToolCalls(message.toolCalls()));
                }
                wire.add(item);
            }
        }
        return wire;
    }

    private List<Map<String, Object>> toWireTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.inputSchema());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("type", "function");
            item.put("function", function);
            wire.add(item);
        }
        return wire;
    }

    private List<Map<String, Object>> toOpenAiToolCalls(List<ToolCall> calls) {
        List<Map<String, Object>> wire = new ArrayList<>();
        for (ToolCall call : calls) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", call.name());
            function.put("arguments", call.inputJson());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", call.id());
            item.put("type", "function");
            item.put("function", function);
            wire.add(item);
        }
        return wire;
    }

    /**
     * 解析 OpenAI SSE data，提取 choices[0].delta.content 文本增量。
     */
    private void handleData(String data, StreamCallback callback, StringBuilder fullText,
                            Map<Integer, ToolCallBuilder> toolCalls) {
        if ("[DONE]".equals(data)) {
            return;
        }
        debug("OpenAI SSE data: " + data);

        try {
            JsonNode root = JSON.readTree(data);
            JsonNode content = root.at("/choices/0/delta/content");
            if (content.isTextual()) {
                String text = content.asText();
                fullText.append(text);
                callback.onText(text);
            }
            JsonNode toolCallNodes = root.at("/choices/0/delta/tool_calls");
            if (toolCallNodes.isArray()) {
                for (JsonNode node : toolCallNodes) {
                    int index = node.path("index").asInt();
                    ToolCallBuilder builder = toolCalls.computeIfAbsent(index, ignored -> new ToolCallBuilder());
                    if (node.path("id").isTextual()) {
                        String id = node.path("id").asText();
                        if (!id.isEmpty()) {
                            builder.id = id;
                        }
                    }
                    JsonNode function = node.path("function");
                    if (function.path("name").isTextual()) {
                        String name = function.path("name").asText();
                        if (!name.isEmpty()) {
                            builder.name = name;
                        }
                    }
                    if (function.path("arguments").isTextual()) {
                        builder.arguments.append(function.path("arguments").asText());
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI SSE data: " + data, e);
        }
    }

    private void debug(String message) {
        if (DEBUG_TOOL_CALLS) {
            System.err.println("[MewCode][OpenAI] " + message);
        }
    }

    private String redactSecrets(String json) {
        return json.replace(config.apiKey(), "***");
    }

    private List<ToolCall> buildToolCalls(Map<Integer, ToolCallBuilder> builders) {
        List<ToolCall> calls = new ArrayList<>();
        for (Map.Entry<Integer, ToolCallBuilder> entry : builders.entrySet()) {
            ToolCallBuilder builder = entry.getValue();
            if (builder.name != null && !builder.name.isEmpty()) {
                String id = builder.id == null || builder.id.isEmpty() ? "call_" + entry.getKey() : builder.id;
                calls.add(new ToolCall(id, builder.name, builder.arguments.toString()));
            }
        }
        return calls;
    }

    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }
}
