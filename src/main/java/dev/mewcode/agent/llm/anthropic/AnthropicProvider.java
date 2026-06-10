package dev.mewcode.agent.llm.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.config.ThinkingConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.StreamCallback;
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

public final class AnthropicProvider implements LlmProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final LlmConfig config;

    public AnthropicProvider(LlmConfig config) {
        this.config = config;
    }

    /**
     * 返回当前 Provider 的展示名称。
     */
    @Override
    public String name() {
        return "Anthropic";
    }

    /**
     * 调用 Anthropic Messages 流式接口，并把文本增量回调给终端层。
     */
    @Override
    public String streamChat(List<ChatMessage> messages, StreamCallback callback) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("max_tokens", config.effectiveMaxTokens());
        payload.put("stream", true);
        payload.put("messages", toWireMessages(messages));

        ThinkingConfig thinking = config.thinking();
        if (thinking != null && thinking.isEnabled()) {
            // thinking 是 Anthropic 特有能力，只在 Provider 内部处理，不泄漏给 TUI。
            Map<String, Object> thinkingPayload = new LinkedHashMap<>();
            thinkingPayload.put("type", "enabled");
            thinkingPayload.put("budget_tokens", thinking.effectiveBudgetTokens());
            thinkingPayload.put("display", thinking.effectiveDisplay());
            payload.put("thinking", thinkingPayload);
        }

        HttpURLConnection connection = openConnection(endpoint(), JSON.writeValueAsString(payload));
        ensureSuccess(connection);
        StringBuilder fullText = new StringBuilder();
        SseClient.consume(connection.getInputStream(), data -> handleData(data, callback, fullText));
        return fullText.toString();
    }

    /**
     * 创建并写入 Anthropic HTTP 请求。响应体后续由 SSE 读取器流式消费。
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
     * 检查 HTTP 状态码，失败时带上 request-id 便于定位。
     */
    private void ensureSuccess(HttpURLConnection connection) throws IOException {
        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalStateException("Anthropic API request failed with HTTP " + statusCode
                    + ", request id: " + valueOrUnknown(connection.getHeaderField("request-id")));
        }
    }

    /**
     * 将空值统一展示为 unknown。
     */
    private String valueOrUnknown(String value) {
        return value == null || value.trim().isEmpty() ? "unknown" : value;
    }

    /**
     * 生成 Messages endpoint，兼容 base_url 和完整 endpoint 两种配置。
     */
    private URI endpoint() {
        String base = config.baseUrl().replaceAll("/+$", "");
        if (base.endsWith("/messages")) {
            return URI.create(base);
        }
        return URI.create(base + "/messages");
    }

    /**
     * 将内部消息结构转换为 Anthropic API 接收的 messages 数组。
     */
    private List<Map<String, String>> toWireMessages(List<ChatMessage> messages) {
        List<Map<String, String>> wire = new ArrayList<>();
        for (ChatMessage message : messages) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", message.role().wireName());
            item.put("content", message.content());
            wire.add(item);
        }
        return wire;
    }

    /**
     * 解析 Anthropic SSE data，只处理文本 delta，忽略 start/stop/ping 等控制事件。
     */
    private void handleData(String data, StreamCallback callback, StringBuilder fullText) {
        try {
            JsonNode root = JSON.readTree(data);
            String type = root.path("type").asText();
            if (!"content_block_delta".equals(type)) {
                return;
            }

            JsonNode delta = root.path("delta");
            String deltaType = delta.path("type").asText();
            if ("text_delta".equals(deltaType)) {
                String text = delta.path("text").asText();
                fullText.append(text);
                callback.onText(text);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Anthropic SSE data: " + data, e);
        }
    }
}
