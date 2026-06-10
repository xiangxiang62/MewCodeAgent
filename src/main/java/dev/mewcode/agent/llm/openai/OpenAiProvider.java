package dev.mewcode.agent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.config.LlmConfig;
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

public final class OpenAiProvider implements LlmProvider {
    private static final ObjectMapper JSON = new ObjectMapper();

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
    public String streamChat(List<ChatMessage> messages, StreamCallback callback) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.model());
        payload.put("stream", true);
        payload.put("messages", toWireMessages(messages));

        HttpURLConnection connection = openConnection(endpoint(), JSON.writeValueAsString(payload));
        ensureSuccess(connection);
        StringBuilder fullText = new StringBuilder();
        SseClient.consume(connection.getInputStream(), data -> handleData(data, callback, fullText));
        return fullText.toString();
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
     * 解析 OpenAI SSE data，提取 choices[0].delta.content 文本增量。
     */
    private void handleData(String data, StreamCallback callback, StringBuilder fullText) {
        if ("[DONE]".equals(data)) {
            return;
        }

        try {
            JsonNode root = JSON.readTree(data);
            JsonNode content = root.at("/choices/0/delta/content");
            if (content.isTextual()) {
                String text = content.asText();
                fullText.append(text);
                callback.onText(text);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI SSE data: " + data, e);
        }
    }
}
