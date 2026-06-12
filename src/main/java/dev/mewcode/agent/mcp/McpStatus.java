package dev.mewcode.agent.mcp;

import java.util.function.Consumer;

/**
 * 维护 MCP 后台加载状态，并在状态变化时通知终端界面刷新提示。
 */
public final class McpStatus {
    private String summary;
    private Consumer<String> listener;

    public McpStatus(String initialSummary) {
        this.summary = initialSummary;
    }

    /**
     * 返回当前可展示的 MCP 状态摘要。
     */
    public synchronized String summary() {
        return summary;
    }

    /**
     * 更新 MCP 状态摘要，并在有监听器时通知界面。
     */
    public void update(String nextSummary) {
        Consumer<String> currentListener;
        synchronized (this) {
            summary = nextSummary;
            currentListener = listener;
        }
        if (currentListener != null) {
            currentListener.accept(nextSummary);
        }
    }

    /**
     * 注册状态变化监听器。终端启动后用它来刷新状态提示。
     */
    public synchronized void setListener(Consumer<String> listener) {
        this.listener = listener;
    }
}
