package dev.mewcode.agent.llm;

/**
 * 接收流式文本增量的回调接口。
 */
@FunctionalInterface
public interface StreamCallback {
    /**
     * 收到一段模型流式文本增量时触发。
     */
    void onText(String text);
}
