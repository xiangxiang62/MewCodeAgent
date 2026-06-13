package dev.mewcode.agent.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示模型返回的一条记忆更新动作。
 */
public record UpdateAction(
        @JsonProperty("action") String action,
        @JsonProperty("level") String level,
        @JsonProperty("type") String type,
        @JsonProperty("slug") String slug,
        @JsonProperty("title") String title,
        @JsonProperty("content") String content,
        @JsonProperty("filename") String filename) {
}
