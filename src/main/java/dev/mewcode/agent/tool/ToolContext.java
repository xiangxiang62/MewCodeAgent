package dev.mewcode.agent.tool;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 工具执行上下文，目前负责向工具传播取消信号。
 */
public final class ToolContext {
    private final AtomicBoolean cancelled;

    /**
     * 使用外部取消标记创建上下文。
     */
    public ToolContext(AtomicBoolean cancelled) {
        this.cancelled = cancelled;
    }

    /**
     * 创建一个新的、未取消的上下文。
     */
    public static ToolContext fresh() {
        return new ToolContext(new AtomicBoolean(false));
    }

    /**
     * 返回可共享的取消标记。
     */
    public AtomicBoolean cancelled() {
        return cancelled;
    }
}
