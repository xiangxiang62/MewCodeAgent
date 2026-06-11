package dev.mewcode.agent.llm.anthropic;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmRequest;
import dev.mewcode.agent.llm.Role;
import dev.mewcode.agent.llm.ToolDefinition;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AnthropicProviderTest {
    @Test
    public void buildsSeparateSystemBlocksWithCacheControl() {
        AnthropicProvider provider = new AnthropicProvider(config());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> system = (List<Map<String, Object>>) provider.buildSystemPayload(
                "stable prompt",
                "Environment Information:\nDate: 2026-06-10");

        assertNotNull(system);
        assertEquals(2, system.size());
        assertEquals("text", system.get(0).get("type"));
        assertTrue(system.get(0).containsKey("cache_control"));
        assertFalse(system.get(1).containsKey("cache_control"));
    }

    @Test
    public void appendsReminderToLastUserMessage() {
        AnthropicProvider provider = new AnthropicProvider(config());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(new ChatMessage(Role.USER, "先做调研"));
        messages.add(new ChatMessage(Role.ASSISTANT, "好的"));
        messages.add(new ChatMessage(Role.USER, "继续"));
        LlmRequest request = new LlmRequest(
                messages,
                Collections.singletonList(new ToolDefinition("glob", "查找文件", Collections.<String, Object>emptyMap())),
                "stable prompt",
                "Environment Information:\nDate: 2026-06-10",
                "<system-reminder>\nplan\n</system-reminder>");

        Map<String, Object> payload = provider.buildPayload(request);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wireMessages = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(3, wireMessages.size());
        assertEquals("user", wireMessages.get(2).get("role"));
        String content = (String) wireMessages.get(2).get("content");
        assertTrue(content.contains("继续"));
        assertTrue(content.contains("<system-reminder>"));
    }

    private LlmConfig config() {
        LlmConfig config = new LlmConfig();
        config.setProtocol("anthropic");
        config.setModel("claude-test");
        config.setBaseUrl("https://example.com/v1");
        config.setApiKey("test-key");
        return config;
    }
}
