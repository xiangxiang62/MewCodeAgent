package dev.mewcode.agent.tool;

import java.nio.charset.StandardCharsets;

/**
 * 控制文本返回大小，避免工具结果过长压爆上下文。
 */
public final class Truncate {
    private Truncate() {
    }

    /**
     * 同时按行数和 UTF-8 字节数截断文本。
     */
    public static String byLinesAndBytes(String text, int maxLines, int maxBytes) {
        String value = text == null ? "" : text;
        StringBuilder out = new StringBuilder();
        String[] lines = value.split("\\R", -1);
        boolean truncated = false;
        int bytes = 0;
        int lineLimit = Math.min(lines.length, maxLines);
        for (int i = 0; i < lineLimit; i++) {
            String piece = (i == 0 ? "" : System.lineSeparator()) + lines[i];
            int nextBytes = bytes + piece.getBytes(StandardCharsets.UTF_8).length;
            if (nextBytes > maxBytes) {
                truncated = true;
                break;
            }
            out.append(piece);
            bytes = nextBytes;
        }
        if (lines.length > maxLines) {
            truncated = true;
        }
        if (truncated) {
            if (out.length() > 0) {
                out.append(System.lineSeparator());
            }
            out.append("[truncated]");
        }
        return out.toString();
    }
}
