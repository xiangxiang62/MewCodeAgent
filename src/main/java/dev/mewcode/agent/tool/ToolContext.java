package dev.mewcode.agent.tool;

import java.util.concurrent.atomic.AtomicBoolean;

public final class ToolContext {
    private final AtomicBoolean cancelled;

    public ToolContext(AtomicBoolean cancelled) {
        this.cancelled = cancelled;
    }

    public static ToolContext fresh() {
        return new ToolContext(new AtomicBoolean(false));
    }

    public AtomicBoolean cancelled() {
        return cancelled;
    }
}
