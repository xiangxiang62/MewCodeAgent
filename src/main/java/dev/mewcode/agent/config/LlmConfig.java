package dev.mewcode.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 模型提供方相关配置。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class LlmConfig {
    private String protocol;
    private String model;

    @JsonProperty("base_url")
    private String baseUrl;

    @JsonProperty("api_key")
    private String apiKey;

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    @JsonProperty("context_window")
    private Integer contextWindow;

    private ThinkingConfig thinking;

    /**
     * 返回协议标识，例如 `openai` 或 `anthropic`。
     */
    public String protocol() {
        return protocol;
    }

    /**
     * 设置协议标识。
     */
    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    /**
     * 返回模型名称。
     */
    public String model() {
        return model;
    }

    /**
     * 设置模型名称。
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 返回接口基础地址。
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 设置接口基础地址。
     */
    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /**
     * 返回 API Key。
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * 设置 API Key。
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 返回配置中的最大输出 token 数。
     */
    public Integer maxTokens() {
        return maxTokens;
    }

    /**
     * 设置最大输出 token 数。
     */
    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * 返回配置中的上下文窗口。
     */
    public Integer contextWindow() {
        return contextWindow;
    }

    /**
     * 设置上下文窗口。
     */
    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    /**
     * 返回扩展 thinking 配置。
     */
    public ThinkingConfig thinking() {
        return thinking;
    }

    /**
     * 设置扩展 thinking 配置。
     */
    public void setThinking(ThinkingConfig thinking) {
        this.thinking = thinking;
    }

    /**
     * 返回生效的最大输出 token 数；未配置时使用默认值。
     */
    public int effectiveMaxTokens() {
        return maxTokens == null ? 4096 : maxTokens;
    }

    /**
     * 返回生效的上下文窗口；未配置时按协议使用默认值。
     */
    public int effectiveContextWindow() {
        if (contextWindow != null && contextWindow.intValue() > 0) {
            return contextWindow.intValue();
        }
        return ProtocolDefaults.defaultContextWindow(protocol);
    }
}
