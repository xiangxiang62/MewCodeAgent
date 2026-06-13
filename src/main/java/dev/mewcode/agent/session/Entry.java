package dev.mewcode.agent.session;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 表示会话 JSONL 文件中的一行记录。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Entry(
        @JsonProperty("type") String type,
        @JsonProperty("role") String role,
        @JsonProperty("content") String content,
        @JsonProperty("tool_calls") List<EntryToolCall> toolCalls,
        @JsonProperty("tool_results") List<EntryToolResult> toolResults,
        @JsonProperty("ts") long ts,
        @JsonProperty("model") String model) {

    /**
     * JSONL 中的工具调用投影结构，避免直接序列化运行时对象。
     */
    public record EntryToolCall(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input_json") String inputJson) {
    }

    /**
     * JSONL 中的工具结果投影结构。
     */
    public record EntryToolResult(
            @JsonProperty("tool_call_id") String toolCallId,
            @JsonProperty("content") String content,
            @JsonProperty("error") boolean error) {
    }
}
