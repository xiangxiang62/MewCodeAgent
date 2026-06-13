package dev.mewcode.agent.llm;

/**
 * 流式输出回调接口。
 */
public interface StreamCallback {
    /**
     * 接收一段新的文本增量。
     */
    void onText(String text);

    /**
     * 返回一个什么都不做的回调，便于内部摘要请求复用。
     */
    static StreamCallback noop() {
        return text -> {
        };
    }
}
