package dev.mewcode.agent.config;

/**
 * 维护不同协议的默认上下文窗口设置。
 */
public final class ProtocolDefaults {
    public static final int DEFAULT_ANTHROPIC_CONTEXT_WINDOW = 200000;
    public static final int DEFAULT_OPENAI_CONTEXT_WINDOW = 128000;

    private ProtocolDefaults() {
    }

    /**
     * 根据协议返回默认上下文窗口；未知协议使用保守默认值。
     */
    public static int defaultContextWindow(String protocol) {
        if ("openai".equalsIgnoreCase(protocol)) {
            return DEFAULT_OPENAI_CONTEXT_WINDOW;
        }
        return DEFAULT_ANTHROPIC_CONTEXT_WINDOW;
    }
}
