package dev.mewcode.agent.compact.state;

import dev.mewcode.agent.compact.CompactConstants;

import java.util.concurrent.locks.ReentrantLock;

/**
 * 跟踪自动压缩的连续失败次数，并提供熔断判断。
 */
public final class AutoCompactTrackingState {
    private final ReentrantLock lock = new ReentrantLock();
    private int consecutiveFailures;

    /**
     * 记录一次自动压缩成功，并清空连续失败计数。
     */
    public void recordSuccess() {
        lock.lock();
        try {
            consecutiveFailures = 0;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 记录一次自动压缩失败。
     */
    public void recordFailure() {
        lock.lock();
        try {
            consecutiveFailures++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 判断是否已经进入熔断状态。
     */
    public boolean tripped() {
        lock.lock();
        try {
            return consecutiveFailures >= CompactConstants.MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES;
        } finally {
            lock.unlock();
        }
    }
}
