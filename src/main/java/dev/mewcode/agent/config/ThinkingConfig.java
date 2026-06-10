package dev.mewcode.agent.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class ThinkingConfig {
    private Boolean enabled;

    @JsonProperty("budget_tokens")
    private Integer budgetTokens;

    private String display;

    /**
     * 返回是否启用 extended thinking 的原始配置值。
     */
    public Boolean enabled() {
        return enabled;
    }

    /**
     * 设置是否启用 extended thinking。
     */
    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 返回 thinking token 预算，可能为空。
     */
    public Integer budgetTokens() {
        return budgetTokens;
    }

    /**
     * 设置 thinking token 预算，对应 YAML 的 budget_tokens。
     */
    public void setBudgetTokens(Integer budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    /**
     * 返回 thinking 展示策略。
     */
    public String display() {
        return display;
    }

    /**
     * 设置 thinking 展示策略。
     */
    public void setDisplay(String display) {
        this.display = display;
    }

    /**
     * 判断 extended thinking 是否显式启用。
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 返回有效的 thinking token 预算；未配置时使用默认值。
     */
    public int effectiveBudgetTokens() {
        return budgetTokens == null ? 1024 : budgetTokens;
    }

    /**
     * 返回有效展示策略；未配置时默认不展示 thinking 内容。
     */
    public String effectiveDisplay() {
        return display == null || display.trim().isEmpty() ? "omitted" : display;
    }
}
