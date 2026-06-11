package dev.mewcode.agent.runtime;

import dev.mewcode.agent.llm.ToolCall;
import dev.mewcode.agent.permission.Outcome;

/**
 * 负责处理工具调用触发人工确认时的交互。
 */
public interface ApprovalHandler {
    /**
     * 请求用户对当前工具调用做出批准决定。
     */
    Outcome requestApproval(ToolCall call, String argsPreview, String reason);
}
