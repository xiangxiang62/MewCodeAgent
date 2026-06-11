package dev.mewcode.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 应用顶层配置对象。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class AppConfig {
    private LlmConfig llm;

    /**
     * 返回 LLM 相关配置。
     */
    public LlmConfig llm() {
        return llm;
    }

    /**
     * 供 Jackson 反序列化时注入 LLM 配置。
     */
    public void setLlm(LlmConfig llm) {
        this.llm = llm;
    }
}
