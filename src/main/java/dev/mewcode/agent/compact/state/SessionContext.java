package dev.mewcode.agent.compact.state;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.SplittableRandom;
import java.util.logging.Logger;

/**
 * 保存当前进程会话的上下文压缩目录信息。
 */
public record SessionContext(String sessionId, Path sessionDir, Path spillDir) {
    private static final Logger LOGGER = Logger.getLogger(SessionContext.class.getName());
    private static final DateTimeFormatter SESSION_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 为当前工作区创建新的会话目录。
     */
    public static SessionContext create(Path workspace) throws IOException {
        String sessionId = newSessionId();
        Path sessionDir = workspace.resolve(".mewcode").resolve("sessions").resolve(sessionId);
        Path spillDir = sessionDir.resolve("tool-results");
        Files.createDirectories(spillDir);
        return new SessionContext(sessionId, sessionDir, spillDir);
    }

    /**
     * 从已存在的会话目录重新打开会话上下文。
     */
    public static SessionContext open(Path workspace, String sessionId) throws IOException {
        Path sessionDir = workspace.resolve(".mewcode").resolve("sessions").resolve(sessionId);
        if (!Files.exists(sessionDir) || !Files.isDirectory(sessionDir)) {
            throw new IOException("Session directory not found: " + sessionDir);
        }
        return new SessionContext(sessionId, sessionDir, sessionDir.resolve("tool-results"));
    }

    /**
     * 解析新格式 session id 中的本地时间戳。
     */
    public static LocalDateTime parseSessionTime(String sessionId) throws DateTimeParseException {
        if (sessionId == null || sessionId.length() < 15) {
            throw new DateTimeParseException("invalid session id", String.valueOf(sessionId), 0);
        }
        return LocalDateTime.parse(sessionId.substring(0, 15), SESSION_ID_TIME);
    }

    /**
     * 生成形如 YYYYMMDD-HHMMSS-xxxx 的会话 id。
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
        return SESSION_ID_TIME.format(LocalDateTime.now()) + "-"
                + HexFormat.of().formatHex(bytes).substring(0, 4);
    }
}
