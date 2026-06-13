package dev.mewcode.agent.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.config.ThinkingConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.ChatResponse;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.PromptTooLongException;
import dev.mewcode.agent.llm.Role;
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
 * Anthropic Messages 协议适配器。
 */
public final class AnthropicProvider implements LlmProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmConfig config;

    /**
     * 创建 Anthropic Provider。
     */
    public AnthropicProvider(LlmConfig config) {
        this.config = config;
    }

    /**
     * 返回 Provider 显示名。
     */
    @Override
    public String name() {
        return "Anthropic";
    }

    /**
     * 返回当前模型名。
     */
    @Override
    public String model() {
        return config.model();
    }

    /**
     * 发起 Anthropic 流式请求，并持续把文本增量回调给 UI。
     */
    @Override
    public ChatResponse streamChat(LlmRequest request, StreamCallback callback) throws Exception {
        Map<String, Object> payload = buildPayload(request);
        ThinkingConfig thinking = config.thinking();
        if (thinking != null && thinking.isEnabled()) {
            // thinking 是 Anthropic 特有能力，只在 Provider 层内部处理。
            Map<String, Object> thinkingPayload = new LinkedHashMap<String, Object>();
            thinkingPayload.put("type", "enabled");
            thinkingPayload.put("budget_tokens", thinking.effectiveBudgetTokens());
            thinkingPayload.put("display", thinking.effectiveDisplay());
            payload.put("thinking", thinkingPayload);
        }

        HttpURLConnection connection = openConnection(endpoint(), JSON.writeValueAsString(payload));
        ensureSuccess(connection);
        StringBuilder fullText = new StringBuilder();
        Map<Integer, ToolUseBuilder> toolUses = new LinkedHashMap<Integer, ToolUseBuilder>();
        UsageHolder usageHolder = new UsageHolder();
        SseClient.consume(connection.getInputStream(),
                data -> handleData(data, callback, fullText, toolUses, usageHolder));
        return new ChatResponse(fullText.toString(), buildToolCalls(toolUses), usageHolder.toUsage());
    }

    /**
     * 组装 Anthropic 协议请求体。
     */
    Map<String, Object> buildPayload(LlmRequest request) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("model", config.model());
        payload.put("max_tokens", config.effectiveMaxTokens());
        payload.put("stream", true);
        Object system = buildSystemPayload(request.systemPrompt(), request.environmentInfo());
        if (system != null) {
            payload.put("system", system);
        }
        payload.put("messages", toWireMessages(request.messages(), request.reminder()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            payload.put("tools", toWireTools(request.tools()));
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
        connection.setRequestProperty("x-api-key", config.apiKey());
        connection.setRequestProperty("anthropic-version", "2023-06-01");
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
     * 校验 HTTP 状态码，失败时带上 request id。
     */
    private void ensureSuccess(HttpURLConnection connection) throws IOException {
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            String message = "Anthropic API request failed with HTTP " + statusCode
                    + ", request id: " + valueOrUnknown(connection.getHeaderField("request-id"))
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
     * 兼容 `base_url` 既可能为根路径，也可能已经指向完整 endpoint。
     */
    private URI endpoint() {
        String base = config.baseUrl().replaceAll("/+$", "");
        if (base.endsWith("/messages")) {
            return URI.create(base);
        }
        return URI.create(base + "/messages");
    }

    /**
     * 将内部消息结构转换为 Anthropic `messages`。
     */
    private List<Map<String, Object>> toWireMessages(List<ChatMessage> messages, String reminder) {
        List<Map<String, Object>> wire = new ArrayList<Map<String, Object>>();
        for (ChatMessage message : messages) {
            if (message.role() == Role.SYSTEM) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            if (message.role() == Role.TOOL) {
                item.put("role", "user");
                List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
                for (ToolResult result : message.toolResults()) {
                    Map<String, Object> block = new LinkedHashMap<String, Object>();
                    block.put("type", "tool_result");
                    block.put("tool_use_id", result.toolCallId());
                    block.put("content", result.content());
                    block.put("is_error", result.isError());
                    blocks.add(block);
                }
                item.put("content", blocks);
            } else {
                item.put("role", message.role().wireName());
                if (message.toolCalls().isEmpty()) {
                    item.put("content", message.content());
                } else {
                    List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
                    if (message.content() != null && !message.content().isEmpty()) {
                        Map<String, Object> text = new LinkedHashMap<String, Object>();
                        text.put("type", "text");
                        text.put("text", message.content());
                        blocks.add(text);
                    }
                    for (ToolCall call : message.toolCalls()) {
                        Map<String, Object> block = new LinkedHashMap<String, Object>();
                        block.put("type", "tool_use");
                        block.put("id", call.id());
                        block.put("name", call.name());
                        try {
                            block.put("input", JSON.readValue(call.inputJson(), Map.class));
                        } catch (Exception e) {
                            block.put("input", new LinkedHashMap<String, Object>());
                        }
                        blocks.add(block);
                    }
                    item.put("content", blocks);
                }
            }
            wire.add(item);
        }
        if (reminder != null && !reminder.trim().isEmpty()) {
            appendReminderToUserMessage(wire, reminder);
        }
        return wire;
    }

    /**
     * 构建 Anthropic `system` 字段，拆分稳定提示和环境信息两个块。
     */
    Object buildSystemPayload(String systemPrompt, String environmentInfo) {
        List<Map<String, Object>> blocks = new ArrayList<Map<String, Object>>();
        addSystemBlock(blocks, systemPrompt, true);
        addSystemBlock(blocks, environmentInfo, false);
        if (blocks.isEmpty()) {
            return null;
        }
        return blocks;
    }

    /**
     * 向 system 数组中追加一个文本块；稳定块会带缓存控制。
     */
    private void addSystemBlock(List<Map<String, Object>> blocks, String value, boolean cacheControl) {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        Map<String, Object> block = new LinkedHashMap<String, Object>();
        block.put("type", "text");
        block.put("text", value.trim());
        if (cacheControl) {
            Map<String, Object> cache = new LinkedHashMap<String, Object>();
            cache.put("type", "ephemeral");
            block.put("cache_control", cache);
        }
        blocks.add(block);
    }

    /**
     * 优先把 reminder 合并进最后一条 user 消息，避免产生非法角色序列。
     */
    private void appendReminderToUserMessage(List<Map<String, Object>> wire, String reminder) {
        if (!wire.isEmpty()) {
            Map<String, Object> last = wire.get(wire.size() - 1);
            if ("user".equals(last.get("role"))) {
                Object content = last.get("content");
                if (content instanceof String) {
                    last.put("content", content + System.lineSeparator() + System.lineSeparator() + reminder);
                    return;
                }
                if (content instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> blocks = (List<Map<String, Object>>) content;
                    Map<String, Object> text = new LinkedHashMap<String, Object>();
                    text.put("type", "text");
                    text.put("text", reminder);
                    blocks.add(text);
                    return;
                }
            }
        }
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", "user");
        item.put("content", reminder);
        wire.add(item);
    }

    /**
     * 将工具定义转换为 Anthropic `tools` 格式。
     */
    private List<Map<String, Object>> toWireTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> wire = new ArrayList<Map<String, Object>>();
        for (ToolDefinition tool : tools) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("input_schema", tool.inputSchema());
            wire.add(item);
        }
        return wire;
    }

    /**
     * 解析 Anthropic SSE 事件，只处理文本和工具 JSON 增量。
     */
    private void handleData(String data, StreamCallback callback, StringBuilder fullText,
            Map<Integer, ToolUseBuilder> toolUses, UsageHolder usageHolder) {
        try {
            JsonNode root = JSON.readTree(data);
            usageHolder.capture(root.path("usage"));
            String type = root.path("type").asText();
            if ("content_block_start".equals(type)) {
                JsonNode block = root.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    int index = root.path("index").asInt();
                    ToolUseBuilder builder = toolUses.computeIfAbsent(index, ignored -> new ToolUseBuilder());
                    builder.id = block.path("id").asText();
                    builder.name = block.path("name").asText();
                }
                return;
            }
            if (!"content_block_delta".equals(type)) {
                return;
            }

            JsonNode delta = root.path("delta");
            String deltaType = delta.path("type").asText();
            if ("text_delta".equals(deltaType)) {
                String text = delta.path("text").asText();
                fullText.append(text);
                callback.onText(text);
            } else if ("input_json_delta".equals(deltaType)) {
                int index = root.path("index").asInt();
                ToolUseBuilder builder = toolUses.computeIfAbsent(index, ignored -> new ToolUseBuilder());
                builder.input.append(delta.path("partial_json").asText());
            }
        } catch (Exception e) {
            if (e instanceof PromptTooLongException) {
                throw (PromptTooLongException) e;
            }
            throw new IllegalStateException("Failed to parse Anthropic SSE data: " + data, e);
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
                || lower.contains("too many tokens")
                || lower.contains("input is too long");
    }

    /**
     * 将工具调用累积器转换为最终工具调用列表。
     */
    private List<ToolCall> buildToolCalls(Map<Integer, ToolUseBuilder> builders) {
        List<ToolCall> calls = new ArrayList<ToolCall>();
        for (Map.Entry<Integer, ToolUseBuilder> entry : builders.entrySet()) {
            ToolUseBuilder builder = entry.getValue();
            if (builder.name != null && !builder.name.isEmpty()) {
                String id = builder.id == null || builder.id.isEmpty() ? "toolu_" + entry.getKey() : builder.id;
                calls.add(new ToolCall(id, builder.name, builder.input.toString()));
            }
        }
        return calls;
    }

    /**
     * 读取失败响应体。
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
     * 保存单个 tool_use 的增量构建状态。
     */
    private static final class ToolUseBuilder {
        private String id;
        private String name;
        private final StringBuilder input = new StringBuilder();
    }

    /**
     * 保存 Anthropic 用量信息的增量累积结果。
     */
    private static final class UsageHolder {
        private long inputTokens;
        private long outputTokens;
        private long cacheWriteTokens;
        private long cacheReadTokens;

        /**
         * 从一条 SSE 事件中提取最新用量字段。
         */
        private void capture(JsonNode usageNode) {
            if (usageNode == null || usageNode.isMissingNode() || usageNode.isNull()) {
                return;
            }
            inputTokens = longValue(usageNode, "input_tokens", inputTokens);
            outputTokens = longValue(usageNode, "output_tokens", outputTokens);
            cacheWriteTokens = longValue(usageNode, "cache_creation_input_tokens", cacheWriteTokens);
            cacheReadTokens = longValue(usageNode, "cache_read_input_tokens", cacheReadTokens);
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
            return new Usage(inputTokens, outputTokens, cacheWriteTokens, cacheReadTokens);
        }
    }
}
