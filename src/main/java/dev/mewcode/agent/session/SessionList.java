package dev.mewcode.agent.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.compact.state.SessionContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 负责扫描本地 session 目录并生成会话列表。
 */
public final class SessionList {
    private static final ObjectMapper JSON = new ObjectMapper();

    private SessionList() {
    }

    /**
     * 扫描 sessions 目录，并按最后修改时间倒序返回会话列表。
     */
    public static List<SessionInfo> list(Path sessionsDir) throws IOException {
        List<SessionInfo> sessions = new ArrayList<SessionInfo>();
        if (sessionsDir == null || !Files.exists(sessionsDir)) {
            return sessions;
        }
        try (var stream = Files.list(sessionsDir)) {
            List<Path> dirs = stream.filter(Files::isDirectory).toList();
            for (Path dir : dirs) {
                String id = dir.getFileName().toString();
                try {
                    SessionContext.parseSessionTime(id).atZone(ZoneId.systemDefault()).toInstant();
                } catch (Exception e) {
                    continue;
                }
                Path conversation = dir.resolve("conversation.jsonl");
                if (!Files.exists(conversation)) {
                    continue;
                }
                String title = id;
                String model = "";
                try (BufferedReader reader = Files.newBufferedReader(conversation, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            Entry entry = JSON.readValue(line, Entry.class);
                            if (model.isEmpty() && entry.model() != null && !entry.model().isBlank()) {
                                model = entry.model();
                            }
                            if ("user".equals(entry.role()) && entry.content() != null && !entry.content().isBlank()) {
                                title = truncate(entry.content(), 50);
                                break;
                            }
                        } catch (Exception ignored) {
                            // 跳过坏行。
                        }
                    }
                }
                FileTime modified = Files.getLastModifiedTime(conversation);
                sessions.add(new SessionInfo(
                        id,
                        title,
                        modified.toInstant(),
                        model,
                        Files.size(conversation),
                        dir));
            }
        }
        sessions.sort(Comparator.comparing(SessionInfo::modifiedAt).reversed());
        return sessions;
    }

    /**
     * 将标题截断到指定长度。
     */
    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }
}
