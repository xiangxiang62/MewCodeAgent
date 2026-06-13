package dev.mewcode.agent.llm;

/**
 * 表示请求超过 provider 的上下文窗口限制。
 */
public final class PromptTooLongException extends RuntimeException {
    /**
     * 仅使用错误消息创建异常。
     */
    public PromptTooLongException(String message) {
        super(message);
    }

    /**
     * 使用错误消息和根因创建异常。
     */
    public PromptTooLongException(String message, Throwable cause) {
        super(message, cause);
    }
}
