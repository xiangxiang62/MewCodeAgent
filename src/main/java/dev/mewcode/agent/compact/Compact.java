package dev.mewcode.agent.compact;

import dev.mewcode.agent.compact.Recovery.RecoveryState;
import dev.mewcode.agent.compact.state.AutoCompactTrackingState;
import dev.mewcode.agent.compact.state.ContentReplacementState;
import dev.mewcode.agent.compact.state.SessionContext;
import dev.mewcode.agent.llm.ChatMessage;
import dev.mewcode.agent.llm.LlmProvider;
import dev.mewcode.agent.llm.ToolDefinition;

import java.util.List;

/**
 * 编排上下文压缩的两层策略。
 */
public final class Compact {
    /**
     * 压缩触发类型。
     */
    public enum TriggerKind {
        AUTO,
        MANUAL,
        EMERGENCY
    }

    /**
     * 压缩后的结果。
     */
    public record CompactResult(List<ChatMessage> messages, long beforeTokens, long afterTokens) {
    }

    private final LlmProvider provider;
    private final String systemPrompt;
    private final String environmentInfo;
    private final ContentReplacementState replacementState;
    private final RecoveryState recoveryState;
    private final AutoCompactTrackingState autoState;
    private final SessionContext sessionContext;
    private final int contextWindow;

    /**
     * 创建上下文压缩编排器。
     */
    public Compact(
            LlmProvider provider,
            String systemPrompt,
            String environmentInfo,
            ContentReplacementState replacementState,
            RecoveryState recoveryState,
            AutoCompactTrackingState autoState,
            SessionContext sessionContext,
            int contextWindow) {
        this.provider = provider;
        this.systemPrompt = systemPrompt;
        this.environmentInfo = environmentInfo;
        this.replacementState = replacementState;
        this.recoveryState = recoveryState;
        this.autoState = autoState;
        this.sessionContext = sessionContext;
        this.contextWindow = contextWindow;
    }

    /**
     * 在普通对话前按需要自动管理上下文。
     */
    public CompactResult manageContext(
            List<ChatMessage> messages,
            List<ToolDefinition> definitions,
            long usageAnchor,
            int usageAnchorMessageCount) throws Exception {
        List<ChatMessage> layer1Messages = Layer1.offloadAndSnip(messages, replacementState, sessionContext);
        long estimated = Token.estimateTokens(usageAnchor, layer1Messages, usageAnchorMessageCount);
        long threshold = contextWindow - CompactConstants.SUMMARY_RESERVE - CompactConstants.AUTO_SAFETY_MARGIN;
        if (estimated < threshold || autoState.tripped()) {
            return new CompactResult(layer1Messages, estimated, estimated);
        }
        try {
            String recovery = Recovery.buildRecoveryAttachment(recoveryState.snapshot(), definitions);
            List<ChatMessage> compacted = Layer2.summarizeWithRetry(
                    provider,
                    layer1Messages,
                    systemPrompt,
                    environmentInfo,
                    definitions,
                    recovery);
            long after = Token.estimateTokens(0L, compacted, 0);
            autoState.recordSuccess();
            return new CompactResult(compacted, estimated, after);
        } catch (Exception e) {
            autoState.recordFailure();
            throw e;
        }
    }

    /**
     * 无条件执行一次手动压缩。
     */
    public CompactResult forceCompact(List<ChatMessage> messages, List<ToolDefinition> definitions, long usageAnchor,
            int usageAnchorMessageCount) throws Exception {
        long before = Token.estimateTokens(usageAnchor, messages, usageAnchorMessageCount);
        String recovery = Recovery.buildRecoveryAttachment(recoveryState.snapshot(), definitions);
        List<ChatMessage> compacted = Layer2.summarizeWithRetry(
                provider,
                messages,
                systemPrompt,
                environmentInfo,
                definitions,
                recovery);
        long after = Token.estimateTokens(0L, compacted, 0);
        return new CompactResult(compacted, before, after);
    }

    /**
     * 紧急压缩前先强制执行一次 layer1，再继续摘要。
     */
    public CompactResult emergencyCompact(
            List<ChatMessage> messages,
            List<ToolDefinition> definitions,
            long usageAnchor,
            int usageAnchorMessageCount) throws Exception {
        List<ChatMessage> layer1Messages = Layer1.offloadAndSnip(messages, replacementState, sessionContext);
        return forceCompact(layer1Messages, definitions, usageAnchor, usageAnchorMessageCount);
    }
}
