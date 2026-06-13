package dev.mewcode.agent.memory;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 表示一条已经持久化的记忆笔记。
 */
public record Note(
        @JsonProperty("level") String level,
        @JsonProperty("type") String type,
        @JsonProperty("slug") String slug,
        @JsonProperty("title") String title,
        @JsonProperty("content") String content,
        @JsonProperty("filename") String filename) {
}
