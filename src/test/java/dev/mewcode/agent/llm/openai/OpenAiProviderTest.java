package dev.mewcode.agent.llm.openai;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolDefinition;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenAiProviderTest {
    @Test
    public void buildsSystemAndReminderMessages() {
        OpenAiProvider provider = new OpenAiProvider(config());
        List<ChatMessage> messages = Collections.singletonList(new ChatMessage(Role.USER, "介绍项目"));
        List<ToolDefinition> tools = Collections.singletonList(
                new ToolDefinition("read_file", "读取文件", Collections.<String, Object>emptyMap()));
        LlmRequest request = new LlmRequest(messages, tools, "stable prompt",
                "Environment Information:\nDate: 2026-06-10",
                "<system-reminder>\nplan\n</system-reminder>");

        Map<String, Object> payload = provider.buildPayload(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wireMessages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals("system", wireMessages.get(0).get("role"));
        assertTrue(((String) wireMessages.get(0).get("content")).contains("stable prompt"));
        assertTrue(((String) wireMessages.get(0).get("content")).contains("Environment Information:"));
        assertEquals("user", wireMessages.get(1).get("role"));
        assertEquals("介绍项目", wireMessages.get(1).get("content"));
        assertEquals("user", wireMessages.get(2).get("role"));
        assertTrue(((String) wireMessages.get(2).get("content")).contains("<system-reminder>"));
        assertEquals("auto", payload.get("tool_choice"));
        assertEquals(Boolean.FALSE, payload.get("parallel_tool_calls"));
    }

    @Test
    public void omitsToolsWhenEmpty() {
        OpenAiProvider provider = new OpenAiProvider(config());
        LlmRequest request = new LlmRequest(
                Collections.singletonList(new ChatMessage(Role.USER, "hi")),
                Collections.<ToolDefinition>emptyList(),
                "stable",
                "",
                "");

        Map<String, Object> payload = provider.buildPayload(request);

        assertFalse(payload.containsKey("tools"));
        assertFalse(payload.containsKey("tool_choice"));
    }

    /**
     * 验证 OpenAI 侧可识别上下文过长错误文本。
     */
    @Test
    public void detectsPromptTooLongMessage() {
        assertTrue(OpenAiProvider.looksLikePromptTooLong("prompt_too_long"));
        assertTrue(OpenAiProvider.looksLikePromptTooLong("maximum context length exceeded"));
        assertFalse(OpenAiProvider.looksLikePromptTooLong("internal server error"));
    }

    private LlmConfig config() {
        LlmConfig config = new LlmConfig();
        config.setProtocol("openai");
        config.setModel("test-model");
        config.setBaseUrl("https://example.com/v1");
        config.setApiKey("test-key");
        return config;
    }
}
