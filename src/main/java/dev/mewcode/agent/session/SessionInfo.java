package dev.mewcode.agent.session;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 表示 /resume 会话列表中的一项摘要信息。
 */
public record SessionInfo(
        String id,
        String title,
        Instant modifiedAt,
        String model,
        long size,
        Path dir) {
}
