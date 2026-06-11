package dev.mewcode.agent.llm;

/**
 * 统一保存不同模型协议解析出来的 token 用量信息。
 */
public final class Usage {
    private final long inputTokens;
    private final long outputTokens;
    private final long cacheWriteTokens;
    private final long cacheReadTokens;

    /**
     * 创建一份用量快照。
     */
    public Usage(long inputTokens, long outputTokens, long cacheWriteTokens, long cacheReadTokens) {
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheWriteTokens = cacheWriteTokens;
        this.cacheReadTokens = cacheReadTokens;
    }

    /**
     * 返回空用量对象。
     */
    public static Usage zero() {
        return new Usage(0L, 0L, 0L, 0L);
    }

    /**
     * 返回输入 token 数。
     */
    public long inputTokens() {
        return inputTokens;
    }

    /**
     * 返回输出 token 数。
     */
    public long outputTokens() {
        return outputTokens;
    }

    /**
     * 返回缓存写入 token 数。
     */
    public long cacheWriteTokens() {
        return cacheWriteTokens;
    }

    /**
     * 返回缓存命中读取 token 数。
     */
    public long cacheReadTokens() {
        return cacheReadTokens;
    }
}
