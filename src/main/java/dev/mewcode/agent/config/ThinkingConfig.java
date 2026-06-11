package dev.mewcode.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Anthropic 扩展 thinking 相关配置。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class ThinkingConfig {
    private Boolean enabled;

    @JsonProperty("budget_tokens")
    private Integer budgetTokens;

    private String display;

    /**
     * 返回 thinking 是否启用的原始配置值。
     */
    public Boolean enabled() {
        return enabled;
    }

    /**
     * 设置 thinking 是否启用。
     */
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回 thinking token 预算。
     */
    public Integer budgetTokens() {
        return budgetTokens;
    }

    /**
     * 设置 thinking token 预算。
     */
    public void setBudgetTokens(Integer budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    /**
     * 返回 thinking 显示策略。
     */
    public String display() {
        return display;
    }

    /**
     * 设置 thinking 显示策略。
     */
    public void setDisplay(String display) {
        this.display = display;
    }

    /**
     * 判断 thinking 是否被显式启用。
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 返回生效的 thinking token 预算。
     */
    public int effectiveBudgetTokens() {
        return budgetTokens == null ? 1024 : budgetTokens;
    }

    /**
     * 返回生效的显示策略；未配置时默认隐藏 thinking 内容。
     */
    public String effectiveDisplay() {
        return display == null || display.trim().isEmpty() ? "omitted" : display;
    }
}
