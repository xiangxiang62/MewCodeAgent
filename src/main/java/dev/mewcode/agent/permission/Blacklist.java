package dev.mewcode.agent.permission;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内置危险命令黑名单。
 * 这里只做启发式高危拦截，不追求穷尽所有危险命令，也不允许通过配置绕过。
 */
public final class Blacklist {
    private static final List<Pattern> PATTERNS = Arrays.asList(
            Pattern.compile("(^|\\s)rm\\s+(-[A-Za-z]*[rR][A-Za-z]*[fF][A-Za-z]*|-+[A-Za-z]*[fF][A-Za-z]*[rR][A-Za-z]*)\\s+(/|~|\\$HOME|/\\*)"),
            Pattern.compile("(^|\\s)dd\\s+.*\\bof=/dev/(sd[a-z]\\d*|hd[a-z]\\d*|nvme\\d+n\\d+(p\\d+)?|disk\\d+)\\b"),
            Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{[^}]*\\|[^}]*&[^}]*\\}"),
            Pattern.compile("(^|\\s)mkfs(\\.[A-Za-z0-9_]+)?\\s"),
            Pattern.compile(">\\s*/dev/(sd[a-z]\\d*|hd[a-z]\\d*|nvme\\d+n\\d+(p\\d+)?|disk\\d+)"),
            Pattern.compile("(^|\\s)chmod\\s+-R\\s+0?777\\s+/")
    );

    private Blacklist() {
    }

    /**
     * 检查命令是否命中黑名单。
     */
    public static boolean hitsBlacklist(String command) {
        if (command == null) {
            return false;
        }
        for (Pattern pattern : PATTERNS) {
            if (pattern.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}
