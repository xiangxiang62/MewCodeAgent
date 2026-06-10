package dev.mewcode.agent.llm;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.anthropic.AnthropicProvider;
import dev.mewcode.agent.llm.openai.OpenAiProvider;

import java.util.Locale;

public final class LlmProviderFactory {
    private LlmProviderFactory() {
    }

    /**
     * 根据配置中的 protocol 创建对应的 LLM Provider。
     */
    public static LlmProvider create(LlmConfig config) {
        switch (config.protocol().toLowerCase(Locale.ROOT)) {
            case "openai":
                return new OpenAiProvider(config);
            case "anthropic":
            case "claude":
                return new AnthropicProvider(config);
            default:
                throw new IllegalArgumentException("Unsupported llm.protocol: " + config.protocol());
        }
    }
}
