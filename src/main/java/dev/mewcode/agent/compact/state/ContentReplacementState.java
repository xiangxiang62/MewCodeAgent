package dev.mewcode.agent.compact.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 维护工具结果是否替换以及替换后预览文本的冻结账本。
 */
public final class ContentReplacementState {
    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> seenIds = new HashSet<String>();
    private final Map<String, String> replacements = new HashMap<String, String>();

    /**
     * 表示某次替换判断的结果类型。
     */
    public enum Decision {
        KEPT,
        REPLACED,
        SKIP
    }

    /**
     * 表示某次替换判断的结果内容。
     */
    public record DecisionResult(Decision decision, String preview) {
    }

    /**
     * 在同一临界区内完成“查账本 -> 决策 -> 写账本”的原子流程。
     */
    public String decideOnce(String id, String original, Supplier<DecisionResult> decide) {
        lock.lock();
        try {
            if (seenIds.contains(id)) {
                return replacements.getOrDefault(id, original);
            }
            DecisionResult result = decide.get();
            if (result == null || result.decision() == null) {
                return original;
            }
            switch (result.decision()) {
                case KEPT:
                    seenIds.add(id);
                    return original;
                case REPLACED:
                    seenIds.add(id);
                    replacements.put(id, result.preview());
                    return result.preview();
                case SKIP:
                default:
                    return original;
            }
        } finally {
            lock.unlock();
        }
    }
}
