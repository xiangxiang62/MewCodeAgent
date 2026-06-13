package dev.mewcode.agent.compact;

/**
 * 表示上下文压缩过程中的受检异常。
 */
public class CompactException extends Exception {
    /**
     * 使用错误消息创建异常。
     */
    public CompactException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和根因创建异常。
     */
    public CompactException(String message, Throwable cause) {
        super(message, cause);
    }
}
