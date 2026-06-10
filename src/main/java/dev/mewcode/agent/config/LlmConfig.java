package dev.mewcode.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    private ThinkingConfig thinking;

    /**
     * 返回协议标识，例如 openai、anthropic。
     */
    public String protocol() {
        return protocol;
    }

    /**
     * 设置协议标识，供 YAML 反序列化使用。
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
     * 设置模型名称，供 YAML 反序列化使用。
     */
    public void setModel(String model) {
        this.model = model;
    }

    /**
     * 返回 API 基础地址。
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 设置 API 基础地址，对应 YAML 的 base_url。
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
     * 设置 API Key，对应 YAML 的 api_key。
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * 返回配置的最大输出 token 数，可能为空。
     */
    public Integer maxTokens() {
        return maxTokens;
    }

    /**
     * 设置最大输出 token 数，对应 YAML 的 max_tokens。
     */
    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    /**
     * 返回 Claude extended thinking 配置。
     */
    public ThinkingConfig thinking() {
        return thinking;
    }

    /**
     * 设置 Claude extended thinking 配置。
     */
    public void setThinking(ThinkingConfig thinking) {
        this.thinking = thinking;
    }

    /**
     * 返回有效的最大输出 token 数；未配置时使用默认值。
     */
    public int effectiveMaxTokens() {
        return maxTokens == null ? 4096 : maxTokens;
    }
}
