package dev.mewcode.agent.runtime;

/**
 * 表示一次手动压缩的结果。
 */
public record ForceCompactResult(long before, long after, Throwable error) {
}
