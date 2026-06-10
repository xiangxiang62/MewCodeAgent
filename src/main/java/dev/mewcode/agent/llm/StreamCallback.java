package dev.mewcode.agent.llm;

@FunctionalInterface
public interface StreamCallback {
    /**
     * 收到一段模型流式文本增量时触发。
     */
    void onText(String text);
}
