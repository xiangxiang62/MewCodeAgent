package dev.mewcode.agent.compact.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.SplittableRandom;
import java.util.logging.Logger;

/**
 * 保存当前进程会话的上下文压缩目录信息。
 */
public record SessionContext(String sessionId, Path spillDir) {
    private static final Logger LOGGER = Logger.getLogger(SessionContext.class.getName());

    /**
     * 为当前工作区创建新的会话目录。
     */
    public static SessionContext create(Path workspace) throws IOException {
        String sessionId = newSessionId();
        Path spillDir = workspace.resolve(".mewcode")
                .resolve("sessions")
                .resolve(sessionId)
                .resolve("tool-results");
        Files.createDirectories(spillDir);
        return new SessionContext(sessionId, spillDir);
    }

    /**
     * 生成形如 <unix_ts>-<short_random> 的会话 id。
     */
    private static String newSessionId() {
        byte[] bytes = new byte[4];
        try {
            SecureRandom.getInstanceStrong().nextBytes(bytes);
        } catch (Exception e) {
            LOGGER.warning("强随机数不可用，改用 SplittableRandom 生成 sessionId");
            SplittableRandom fallback = new SplittableRandom(System.nanoTime());
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) fallback.nextInt(0, 256);
            }
        }
        return Instant.now().getEpochSecond() + "-" + HexFormat.of().formatHex(bytes);
    }
}
