package dev.mewcode.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.PromptTooLongException;
import dev.mewcode.agent.llm.StreamCallback;
import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.llm.ToolDefinition;
import dev.mewcode.agent.llm.ToolResult;
import dev.mewcode.agent.llm.Usage;
import dev.mewcode.agent.llm.http.SseClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 协议适配器。
 */
public final class OpenAiProvider implements LlmProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final boolean DEBUG_TOOL_CALLS = Boolean.parseBoolean(
            System.getenv().getOrDefault("MEWCODE_DEBUG_TOOL_CALLS", "false"));

    private final LlmConfig config;

    /**
     * 创建 OpenAI Provider。
     */
    public OpenAiProvider(LlmConfig config) {
        this.config = config;
    }

    /**
     * 返回 Provider 显示名。
     */
    @Override
    public String name() {
        return "OpenAI";
    }

    /**
     * 返回当前模型名。
     */
    @Override
    public String model() {
        return config.model();
    }

    /**
     * 发起 OpenAI 流式请求，并在解析增量时同步向 UI 推送文本。
     */
    @Override
    public ChatResponse streamChat(LlmRequest request, StreamCallback callback) throws Exception {
        Map<String, Object> payload = buildPayload(request);
        debug("OpenAI request payload: " + redactSecrets(JSON.writeValueAsString(payload)));

        HttpURLConnection connection = openConnection(endpoint(), JSON.writeValueAsString(payload));
        ensureSuccess(connection);
        StringBuilder fullText = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCalls = new LinkedHashMap<Integer, ToolCallBuilder>();
        UsageHolder usageHolder = new UsageHolder();
        SseClient.consume(connection.getInputStream(),
                data -> handleData(data, callback, fullText, toolCalls, usageHolder));
        return new ChatResponse(fullText.toString(), buildToolCalls(toolCalls), usageHolder.toUsage());
    }

    /**
     * 组装 OpenAI 协议请求体。
     */
    Map<String, Object> buildPayload(LlmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", config.model());
        payload.put("max_tokens", config.effectiveMaxTokens());
        payload.put("stream", true);
        payload.put("messages", toWireMessages(
                request.messages(),
                request.systemPrompt(),
                request.environmentInfo(),
                request.reminder()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", toWireTools(request.tools()));
            payload.put("tool_choice", "auto");
            payload.put("parallel_tool_calls", false);
        }
        return payload;
    }

    /**
     * 创建 HTTP 连接并写入 JSON 请求体。
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
     * 校验 HTTP 返回码，失败时尽量带上 request id 和错误体。
     */
    private void ensureSuccess(HttpURLConnection connection) throws IOException {
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            String message = "OpenAI API request failed with HTTP " + statusCode
                    + ", request id: " + valueOrUnknown(connection.getHeaderField("x-request-id"))
                    + ", body: " + readErrorBody(connection);
            if (looksLikePromptTooLong(message)) {
                throw new PromptTooLongException(message);
            }
            throw new IllegalStateException(message);
        }
    }

    /**
     * 将空值统一显示为 `unknown`。
     */
    private String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }

    /**
     * 兼容 `base_url` 既可能是根路径，也可能已经指向完整 endpoint 的情况。
     */
    private URI endpoint() {
        String base = config.baseUrl().replaceAll("/+$", "");
        if (base.endsWith("/chat/completions")) {
            return URI.create(base);
        }
        return URI.create(base + "/chat/completions");
    }

    /**
     * 将内部消息结构转换成 OpenAI `messages` 数组。
     */
    private List<Map<String, Object>> toWireMessages(
            List<ChatMessage> messages,
            String systemPrompt,
            String environmentInfo,
            String reminder) {
        List<Map<String, Object>> wire = new ArrayList<Map<String, Object>>();
        String systemContent = joinSections(systemPrompt, environmentInfo);
        if (!systemContent.isEmpty()) {
            Map<String, Object> systemMessage = new LinkedHashMap<String, Object>();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemContent);
            wire.add(systemMessage);
        }
        for (ChatMessage message : messages) {
            if (message.role() == dev.mewcode.agent.llm.Role.SYSTEM) {
                continue;
            }
            if (message.role() == dev.mewcode.agent.llm.Role.TOOL) {
                for (ToolResult result : message.toolResults()) {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("role", "tool");
                    item.put("tool_call_id", result.toolCallId());
                    item.put("content", result.content());
                    wire.add(item);
                }
            } else {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("role", message.role().wireName());
                item.put("content", message.content());
                if (!message.toolCalls().isEmpty()) {
                    item.put("tool_calls", toOpenAiToolCalls(message.toolCalls()));
                }
                wire.add(item);
            }
        }
        if (reminder != null && !reminder.trim().isEmpty()) {
            Map<String, Object> reminderMessage = new LinkedHashMap<String, Object>();
            reminderMessage.put("role", "user");
            reminderMessage.put("content", reminder);
            wire.add(reminderMessage);
        }
        return wire;
    }

    /**
     * 将稳定系统提示和环境信息拼成单个 system message。
     */
    private String joinSections(String systemPrompt, String environmentInfo) {
        StringBuilder builder = new StringBuilder();
        appendSection(builder, systemPrompt);
        appendSection(builder, environmentInfo);
        return builder.toString();
    }

    /**
     * 仅在值非空时追加文本段。
     */
    private void appendSection(StringBuilder builder, String value) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        if (builder.length() > 0) {
            builder.append("\n\n");
        }
        builder.append(value.trim());
    }

    /**
     * 将工具定义转换为 OpenAI `tools` 格式。
     */
    private List<Map<String, Object>> toWireTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> wire = new ArrayList<Map<String, Object>>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.put("parameters", tool.inputSchema());
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("type", "function");
            item.put("function", function);
            wire.add(item);
        }
        return wire;
    }

    /**
     * 将内部工具调用结构转换为 OpenAI assistant message 中的 `tool_calls`。
     */
    private List<Map<String, Object>> toOpenAiToolCalls(List<ToolCall> calls) {
        List<Map<String, Object>> wire = new ArrayList<Map<String, Object>>();
        for (ToolCall call : calls) {
            Map<String, Object> function = new LinkedHashMap<String, Object>();
            function.put("name", call.name());
            function.put("arguments", call.inputJson());
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("id", call.id());
            item.put("type", "function");
            item.put("function", function);
            wire.add(item);
        }
        return wire;
    }

    /**
     * 解析 OpenAI SSE 增量，累计文本、工具调用和用量信息。
     */
    private void handleData(
            String data,
            StreamCallback callback,
            StringBuilder fullText,
            Map<Integer, ToolCallBuilder> toolCalls,
            UsageHolder usageHolder) {
        if ("[DONE]".equals(data)) {
            return;
        }
        debug("OpenAI SSE data: " + data);

        try {
            JsonNode root = JSON.readTree(data);
            JsonNode error = root.path("error");
            if (!error.isMissingNode() && !error.isNull()) {
                String message = formatStreamError(error);
                if (looksLikePromptTooLong(message)) {
                    throw new PromptTooLongException(message);
                }
                throw new IllegalStateException(message);
            }
            usageHolder.capture(root.path("usage"));
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
            if (e instanceof PromptTooLongException) {
                throw (PromptTooLongException) e;
            }
            throw new IllegalStateException("Failed to parse OpenAI SSE data: " + data, e);
        }
    }

    /**
     * 判断错误文本是否表达了上下文过长。
     */
    static boolean looksLikePromptTooLong(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("prompt_too_long")
                || lower.contains("context length")
                || lower.contains("maximum context")
                || lower.contains("token limit")
                || lower.contains("too many tokens");
    }

    /**
     * 在调试开关开启时输出内部日志。
     */
    private void debug(String message) {
        if (DEBUG_TOOL_CALLS) {
            System.err.println("[MewCode][OpenAI] " + message);
        }
    }

    /**
     * 避免调试日志中泄露真实 API Key。
     */
    private String redactSecrets(String json) {
        return json.replace(config.apiKey(), "***");
    }

    /**
     * 把增量累积器转换成最终工具调用列表。
     */
    private List<ToolCall> buildToolCalls(Map<Integer, ToolCallBuilder> builders) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (Map.Entry<Integer, ToolCallBuilder> entry : builders.entrySet()) {
            ToolCallBuilder builder = entry.getValue();
            if (builder.name != null && !builder.name.isEmpty()) {
                String id = builder.id == null || builder.id.isEmpty() ? "call_" + entry.getKey() : builder.id;
                calls.add(new ToolCall(id, builder.name, builder.arguments.toString()));
            }
        }
        return calls;
    }

    /**
     * 读取失败响应体，便于上层定位问题。
     */
    private String readErrorBody(HttpURLConnection connection) throws IOException {
        InputStream errorStream = connection.getErrorStream();
        if (errorStream == null) {
            return "empty";
        }
        byte[] bytes = readFully(errorStream);
        if (bytes.length == 0) {
            return "empty";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * 格式化流式返回中的错误对象。
     */
    private String formatStreamError(JsonNode error) {
        String code = error.path("code").asText("");
        String message = error.path("message").asText(error.toString());
        if (!code.isEmpty()) {
            return "模型流式响应出错(code=" + code + "): " + message;
        }
        return "模型流式响应出错: " + message;
    }

    /**
     * 读取整个输入流为字节数组。
     */
    private byte[] readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        return outputStream.toByteArray();
    }

    /**
     * 保存单个工具调用的增量构建状态。
     */
    private static final class ToolCallBuilder {
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 保存流式响应中的用量累积结果。
     */
    private static final class UsageHolder {
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;

        /**
         * 从一条 SSE 事件中提取最新用量字段。
         */
        private void capture(JsonNode usageNode) {
            if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
                return;
            }
            inputTokens = longValue(usageNode, "prompt_tokens", inputTokens);
            outputTokens = longValue(usageNode, "completion_tokens", outputTokens);
            JsonNode details = usageNode.path("prompt_tokens_details");
            if (!details.isMissingNode() && !details.isNull()) {
                cacheReadTokens = longValue(details, "cached_tokens", cacheReadTokens);
            }
        }

        /**
         * 读取数值字段；缺失时保留原值。
         */
        private long longValue(JsonNode node, String field, long fallback) {
            JsonNode value = node.path(field);
            return value.isNumber() ? value.asLong() : fallback;
        }

        /**
         * 转换为统一用量对象。
         */
        private Usage toUsage() {
            return new Usage(inputTokens, outputTokens, 0L, cacheReadTokens);
        }
    }
}
