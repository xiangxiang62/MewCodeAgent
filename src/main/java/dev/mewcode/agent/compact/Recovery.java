package dev.mewcode.agent.compact;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mewcode.agent.llm.ToolDefinition;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 负责恢复段的文件快照与文本构造。
 */
public final class Recovery {
    /** 固定边界提示，提醒模型需要原文时重新读取。 */
    public static final String BOUNDARY_NOTICE = ""
            + "如果你需要文件原文、错误原文或用户的精确原话，请重新使用文件读取工具获取原文。"
            + System.lineSeparator()
            + "不要仅凭摘要或恢复段猜测细节。";

    private static final ObjectMapper JSON = new ObjectMapper();

    private Recovery() {
    }

    /**
     * 表示一次文件读取快照。
     */
    public record FileReadRecord(String path, String content, Instant timestamp) {
    }

    /**
     * 线程安全地记录最近读取过的原始文件内容。
     */
    public static final class RecoveryState {
        private final ReentrantLock lock = new ReentrantLock();
        private final Map<String, FileReadRecord> files = new LinkedHashMap<String, FileReadRecord>();

        /**
         * 记录一次文件读取结果。
         */
        public void recordFile(String path, String content) {
            Path normalized = Path.of(path).toAbsolutePath().normalize();
            lock.lock();
            try {
                files.put(normalized.toString(), new FileReadRecord(normalized.toString(), content, Instant.now()));
            } finally {
                lock.unlock();
            }
        }

        /**
         * 返回按最近读取时间倒序排列的不可变快照。
         */
        public List<FileReadRecord> snapshot() {
            lock.lock();
            try {
                List<FileReadRecord> records = new ArrayList<FileReadRecord>(files.values());
                records.sort(Comparator.comparing(FileReadRecord::timestamp).reversed());
                return List.copyOf(records);
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 构造压缩后的恢复附件。
     */
    public static String buildRecoveryAttachment(List<FileReadRecord> snapshot, List<ToolDefinition> definitions) {
        StringBuilder builder = new StringBuilder();
        builder.append("## 最近读取过的文件").append(System.lineSeparator());
        List<FileReadRecord> limited = snapshot == null ? List.<FileReadRecord>of()
                : snapshot.subList(0, Math.min(snapshot.size(), CompactConstants.RECOVERY_FILE_LIMIT));
        if (limited.isEmpty()) {
            builder.append("(暂无)").append(System.lineSeparator());
        } else {
            for (FileReadRecord record : limited) {
                builder.append(renderFileBlock(record));
            }
        }
        builder.append(System.lineSeparator());
        builder.append("## 当前可用工具").append(System.lineSeparator());
        builder.append(renderToolsBlock(definitions)).append(System.lineSeparator()).append(System.lineSeparator());
        builder.append("## 边界提示").append(System.lineSeparator());
        builder.append(BOUNDARY_NOTICE);
        return builder.toString();
    }

    /**
     * 渲染单个文件快照块。
     */
    static String renderFileBlock(FileReadRecord record) {
        int charLimit = (int) (CompactConstants.RECOVERY_TOKENS_PER_FILE
                * CompactConstants.ESTIMATE_CHARS_PER_TOKEN);
        String content = record.content() == null ? "" : record.content();
        boolean truncated = content.length() > charLimit;
        String snippet = truncated ? content.substring(0, charLimit) : content;
        StringBuilder builder = new StringBuilder();
        builder.append("### ").append(record.path()).append(System.lineSeparator());
        builder.append("[read at] ").append(record.timestamp()).append(System.lineSeparator());
        builder.append(snippet).append(System.lineSeparator());
        if (truncated) {
            builder.append("(content truncated)").append(System.lineSeparator());
        }
        return builder.toString();
    }

    /**
     * 渲染当前工具定义列表。
     */
    static String renderToolsBlock(List<ToolDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) {
            return "(无可用工具)";
        }
        StringBuilder builder = new StringBuilder();
        for (ToolDefinition definition : definitions) {
            builder.append("- ").append(definition.name()).append(": ").append(definition.description())
                    .append(System.lineSeparator());
            try {
                builder.append("  schema: ").append(JSON.writeValueAsString(definition.inputSchema()))
                        .append(System.lineSeparator());
            } catch (Exception e) {
                builder.append("  schema: {}").append(System.lineSeparator());
            }
        }
        return builder.toString().trim();
    }
}
