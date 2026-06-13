package dev.mewcode.agent.config;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ConfigLoaderTest {
    /**
     * 验证包含四个核心字段的 YAML 能被正确加载。
     */
    @Test
    public void loadsRequiredLlmFields() throws Exception {
        Path config = writeConfig(""
                + "llm:\n"
                + "  protocol: openai\n"
                + "  model: gpt-4.1-mini\n"
                + "  base_url: https://api.openai.com/v1\n"
                + "  api_key: test-key\n"
                + "  max_tokens: 123\n"
                + "  context_window: 88888\n");

        AppConfig loaded = ConfigLoader.load(config);

        assertEquals("openai", loaded.llm().protocol());
        assertEquals("gpt-4.1-mini", loaded.llm().model());
        assertEquals("https://api.openai.com/v1", loaded.llm().baseUrl());
        assertEquals("test-key", loaded.llm().apiKey());
        assertEquals(123, loaded.llm().effectiveMaxTokens());
        assertEquals(88888, loaded.llm().effectiveContextWindow());
    }

    /**
     * 验证缺少必填字段时，错误信息能指出具体字段名。
     */
    @Test
    public void reportsMissingRequiredField() throws Exception {
        Path config = writeConfig(""
                + "llm:\n"
                + "  protocol: openai\n"
                + "  model: gpt-4.1-mini\n"
                + "  base_url: https://api.openai.com/v1\n");

        try {
            ConfigLoader.load(config);
            fail("Expected missing api_key to fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("llm.api_key"));
        }
    }

    /**
     * 验证未配置上下文窗口时会按协议返回默认值。
     */
    @Test
    public void fallsBackToProtocolDefaultContextWindow() {
        LlmConfig openai = new LlmConfig();
        openai.setProtocol("openai");
        assertEquals(128000, openai.effectiveContextWindow());

        LlmConfig anthropic = new LlmConfig();
        anthropic.setProtocol("anthropic");
        assertEquals(200000, anthropic.effectiveContextWindow());

        LlmConfig unknown = new LlmConfig();
        unknown.setProtocol("custom");
        assertEquals(200000, unknown.effectiveContextWindow());
    }

    /**
     * 写入临时 YAML 文件，避免测试依赖真实本地配置。
     */
    private Path writeConfig(String yaml) throws Exception {
        Path config = Files.createTempFile("mewcode-config", ".yaml");
        Files.write(config, yaml.getBytes(StandardCharsets.UTF_8));
        return config;
    }
}
