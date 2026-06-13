package dev.mewcode.agent.compact;

/**
 * 维护上下文压缩使用的全部硬编码常量。
 */
public final class CompactConstants {
    /** 单条工具结果超过该字节数时强制落盘。 */
    public static final int SINGLE_RESULT_LIMIT = 50000;

    /** 单轮工具结果聚合超过该字节数时继续落盘。 */
    public static final int MESSAGE_AGGREGATE_LIMIT = 200000;

    /** 为摘要输出预留的 token 空间。 */
    public static final int SUMMARY_RESERVE = 20000;

    /** 自动压缩额外预留的安全余量。 */
    public static final int AUTO_SAFETY_MARGIN = 13000;

    /** 手动压缩摘要请求预检使用的安全余量。 */
    public static final int MANUAL_SAFETY_MARGIN = 3000;

    /** 恢复段最多保留的最近文件数量。 */
    public static final int RECOVERY_FILE_LIMIT = 5;

    /** 单个恢复文件快照的 token 上限。 */
    public static final int RECOVERY_TOKENS_PER_FILE = 5000;

    /** 最近原文尾部至少保留的 token 数。 */
    public static final int RECENT_KEEP_TOKENS = 10000;

    /** 最近原文尾部至少保留的消息条数。 */
    public static final int RECENT_KEEP_MESSAGES = 5;

    /** 自动压缩连续失败达到该次数后进入熔断。 */
    public static final int MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES = 3;

    /** 摘要请求 PTL 直接重试次数上限。 */
    public static final int PTL_RETRY_LIMIT = 3;

    /** PTL 比例裁剪时每次丢弃的历史比例。 */
    public static final double PTL_DROP_PERCENTAGE = 0.2d;

    /** 字符数估算 token 的换算因子。 */
    public static final double ESTIMATE_CHARS_PER_TOKEN = 3.5d;

    /** 预览头部最大字节数。 */
    public static final int PREVIEW_HEAD_BYTES = 2048;

    /** 预览头部最大行数。 */
    public static final int PREVIEW_HEAD_LINES = 20;

    private CompactConstants() {
    }
}
