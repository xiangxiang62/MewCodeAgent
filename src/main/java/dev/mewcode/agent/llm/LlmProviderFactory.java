package dev.mewcode.agent.llm;

import dev.mewcode.agent.config.LlmConfig;
import dev.mewcode.agent.llm.anthropic.AnthropicProvider;
import dev.mewcode.agent.llm.openai.OpenAiProvider;

import java.util.Locale;

/**
 * 根据配置构造具体的模型提供方实现。
 */
public final class LlmProviderFactory {
    private LlmProviderFactory() {
    }

    /**
     * 按 `protocol` 选择并创建对应 Provider。
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
