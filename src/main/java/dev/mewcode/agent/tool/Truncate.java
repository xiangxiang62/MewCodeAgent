package dev.mewcode.agent.tool;

import java.nio.charset.StandardCharsets;

public final class Truncate {
    private Truncate() {
    }

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
