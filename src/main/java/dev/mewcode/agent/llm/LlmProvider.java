package dev.mewcode.agent.llm;

import java.util.List;

public interface LlmProvider {
    /**
     * 返回 Provider 名称，用于启动信息和调试输出。
     */
    String name();

    /**
     * 根据完整对话历史发起流式聊天请求。
     *
     * @return 本轮完整助手回复，用于追加到会话历史中。
     */
    ChatResponse streamChat(List<ChatMessage> messages, List<ToolDefinition> tools, StreamCallback callback) throws Exception;
}
