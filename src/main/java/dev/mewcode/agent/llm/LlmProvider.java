package dev.mewcode.agent.llm;

/**
 * 抽象不同模型协议下的统一调用接口。
 */
public interface LlmProvider {
    /**
     * 返回 Provider 名称，用于启动信息和调试输出。
     */
    String name();

    /**
     * 返回当前使用的模型名。
     */
    String model();

    /**
     * 根据完整会话上下文发起流式请求。
     *
     * @return 本轮聚合后的完整响应
     */
    ChatResponse streamChat(LlmRequest request, StreamCallback callback) throws Exception;
}
