package dev.mewcode.agent.permission;

import java.util.regex.Pattern;

/**
 * 表示一条权限规则。
 */
public final class Rule {
    private final String tool;
    private final String pattern;
    private final boolean allow;

    public Rule(String tool, String pattern, boolean allow) {
        this.tool = tool == null ? "" : tool.trim();
        this.pattern = pattern == null ? "" : pattern;
        this.allow = allow;
    }

    public String tool() {
        return tool;
    }

    public String pattern() {
        return pattern;
    }

    public boolean allow() {
        return allow;
    }

    /**
     * 解析单条规则，支持 Tool 与 Tool(pattern) 两种形式。
     */
    public static Rule parse(String value, boolean allow) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        int left = trimmed.indexOf('(');
        int right = trimmed.lastIndexOf(')');
        if (left < 0 && right < 0) {
            return new Rule(trimmed, "", allow);
        }
        if (left <= 0 || right != trimmed.length() - 1 || right <= left) {
            return null;
        }
        String tool = trimmed.substring(0, left).trim();
        String pattern = trimmed.substring(left + 1, right);
        if (tool.isEmpty()) {
            return null;
        }
        return new Rule(tool, pattern, allow);
    }

    /**
     * 判断规则是否命中目标。
     */
    public boolean matches(String target, boolean filePattern) {
        return matchPattern(pattern, target, filePattern);
    }

    /**
     * 按命令或路径语义进行 glob 匹配。
     */
    public static boolean matchPattern(String pattern, String target, boolean filePattern) {
        if (pattern == null || pattern.isEmpty()) {
            return true;
        }
        String value = target == null ? "" : target.replace('\\', '/');
        String regex = filePattern ? toPathRegex(pattern) : toCommandRegex(pattern);
        return Pattern.compile(regex, Pattern.DOTALL).matcher(value).matches();
    }

    private static String toCommandRegex(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '\\' && i + 1 < pattern.length()) {
                appendLiteral(regex, pattern.charAt(i + 1));
                i++;
                continue;
            }
            if (ch == '*') {
                while (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    i++;
                }
                regex.append(".*");
                continue;
            }
            if (ch == '?') {
                regex.append('.');
                continue;
            }
            appendLiteral(regex, ch);
        }
        regex.append('$');
        return regex.toString();
    }

    private static String toPathRegex(String pattern) {
        String normalized = pattern.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '\\' && i + 1 < normalized.length()) {
                appendLiteral(regex, normalized.charAt(i + 1));
                i++;
                continue;
            }
            if (ch == '*') {
                if (i + 1 < normalized.length() && normalized.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
                continue;
            }
            if (ch == '?') {
                regex.append("[^/]");
                continue;
            }
            appendLiteral(regex, ch);
        }
        regex.append('$');
        return regex.toString();
    }

    private static void appendLiteral(StringBuilder builder, char ch) {
        if ("\\.[]{}()+-^$|".indexOf(ch) >= 0) {
            builder.append('\\');
        }
        builder.append(ch);
    }
}
