package dev.mewcode.agent.session;

import dev.mewcode.agent.compact.state.SessionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.logging.Logger;

/**
 * 负责清理过期会话目录。
 */
public final class SessionCleaner {
    private static final Logger LOGGER = Logger.getLogger(SessionCleaner.class.getName());

    private SessionCleaner() {
    }

    /**
     * 清理超过指定年龄的会话目录。
     */
    public static void cleanExpired(Path sessionsDir, Duration maxAge) {
        if (sessionsDir == null || !Files.exists(sessionsDir)) {
            return;
        }
        try (var stream = Files.list(sessionsDir)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                String id = dir.getFileName().toString();
                try {
                    Instant created = SessionContext.parseSessionTime(id)
                            .atZone(ZoneId.systemDefault())
                            .toInstant();
                    if (Duration.between(created, Instant.now()).compareTo(maxAge) > 0) {
                        deleteRecursively(dir);
                    }
                } catch (Exception ignored) {
                    // 旧格式或坏目录跳过。
                }
            }
        } catch (IOException e) {
            LOGGER.warning("Clean expired sessions failed: " + e.getMessage());
        }
    }

    /**
     * 递归删除目录。
     */
    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOGGER.warning("Delete session dir failed: " + dir + ", " + e.getMessage());
        }
    }
}
