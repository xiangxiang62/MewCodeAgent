package dev.mewcode.agent.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.mewcode.agent.llm.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 负责权限系统的规则加载、权限判定与本地授权持久化。
 */
public final class PermissionEngine {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private final Path root;
    private final Path userSettingsPath;
    private final Path projectSettingsPath;
    private final Path localSettingsPath;
    private final RuleSet userRules;
    private final RuleSet projectRules;
    private RuleSet localRules;
    private final Mode startMode;

    private PermissionEngine(Path root,
            Path userSettingsPath,
            Path projectSettingsPath,
            Path localSettingsPath,
            RuleSet userRules,
            RuleSet projectRules,
            RuleSet localRules,
            Mode startMode) {
        this.root = root;
        this.userSettingsPath = userSettingsPath;
        this.projectSettingsPath = projectSettingsPath;
        this.localSettingsPath = localSettingsPath;
        this.userRules = userRules;
        this.projectRules = projectRules;
        this.localRules = localRules;
        this.startMode = startMode;
    }

    /**
     * 创建权限引擎。
     */
    public static PermissionEngine create(Path rawRoot) {
        Path fallbackRoot = rawRoot == null ? Paths.get(".").toAbsolutePath().normalize() : rawRoot.toAbsolutePath().normalize();
        Path userPath = resolveUserSettingsPath();
        Path projectPath = fallbackRoot.resolve(".mewcode").resolve("settings.yaml");
        Path localPath = fallbackRoot.resolve(".mewcode").resolve("settings.local.yaml");

        Settings user = Settings.load(userPath);
        Settings project = Settings.load(projectPath);
        Settings local = Settings.load(localPath);
        Mode startMode = firstMode(local.defaultMode(), project.defaultMode(), user.defaultMode());

        return new PermissionEngine(
                fallbackRoot,
                userPath,
                projectPath,
                localPath,
                user.toRuleSet(),
                project.toRuleSet(),
                local.toRuleSet(),
                startMode);
    }

    /**
     * 对单次工具调用做权限判定。
     */
    public CheckResult check(Mode mode, ToolCall call, boolean readOnly) {
        Category category = Settings.categorize(call.name(), readOnly);
        String friendly = Settings.friendlyName(call.name());
        Settings.TargetInfo target = Settings.extractTarget(call);

        if (category == Category.EXEC
                && "bash".equals(call.name())
                && target.target() != null
                && !target.target().isEmpty()
                && Blacklist.hitsBlacklist(target.target())) {
            return new CheckResult(Decision.DENY, "命中危险命令黑名单: " + target.target());
        }

        if (target.isFile()) {
            if (!target.ok()) {
                return new CheckResult(Decision.DENY, "无法解析文件路径参数，已拒绝执行");
            }
            try {
                if (!Sandbox.sandboxOK(root, target.target())) {
                    return new CheckResult(Decision.DENY, "路径位于项目目录之外: " + target.target());
                }
            } catch (IOException e) {
                return new CheckResult(Decision.DENY, "路径校验失败: " + target.target());
            }
        }

        String normalizedTarget = normalizedTarget(friendly, target.target());

        Decision local = localRules.match(friendly, normalizedTarget);
        if (local != null) {
            return new CheckResult(local, "匹配本地规则: " + friendlyRuleText(friendly, normalizedTarget, local));
        }

        Decision project = projectRules.match(friendly, normalizedTarget);
        if (project != null) {
            return new CheckResult(project, "匹配项目规则: " + friendlyRuleText(friendly, normalizedTarget, project));
        }

        Decision user = userRules.match(friendly, normalizedTarget);
        if (user != null) {
            return new CheckResult(user, "匹配用户规则: " + friendlyRuleText(friendly, normalizedTarget, user));
        }

        Decision fallback = modeFallback(mode, category);
        if (fallback == Decision.ALLOW) {
            return new CheckResult(Decision.ALLOW, "");
        }
        return new CheckResult(Decision.ASK,
                mode.displayName() + " 模式下，" + category.displayName() + "操作需要确认");
    }

    /**
     * 持久化本地 allow 规则，并立即刷新内存规则。
     */
    public synchronized void persistLocalAllow(ToolCall call) throws IOException {
        String ruleText = buildAllowRule(call);
        if (ruleText == null || ruleText.trim().isEmpty()) {
            return;
        }

        Settings current = Settings.load(localSettingsPath);
        List<String> allow = new ArrayList<String>(current.allow());
        if (!allow.contains(ruleText)) {
            allow.add(ruleText);
        }

        Map<String, Object> rootNode = new LinkedHashMap<String, Object>();
        if (current.defaultMode() != null && !current.defaultMode().trim().isEmpty()) {
            rootNode.put("defaultMode", current.defaultMode());
        }
        Map<String, Object> permissions = new LinkedHashMap<String, Object>();
        permissions.put("allow", allow);
        permissions.put("deny", current.deny());
        rootNode.put("permissions", permissions);

        Files.createDirectories(localSettingsPath.getParent());
        YAML.writeValue(localSettingsPath.toFile(), rootNode);
        this.localRules = new Settings(current.defaultMode(), allow, current.deny()).toRuleSet();
    }

    public Mode startMode() {
        return startMode;
    }

    public Path localSettingsPath() {
        return localSettingsPath;
    }

    public Path userSettingsPath() {
        return userSettingsPath;
    }

    public Path projectSettingsPath() {
        return projectSettingsPath;
    }

    /**
     * 根据模式和操作类别给出兜底判定。
     */
    public static Decision modeFallback(Mode mode, Category category) {
        if (category == Category.READ) {
            return Decision.ALLOW;
        }
        if (mode == Mode.BYPASS) {
            return Decision.ALLOW;
        }
        if (mode == Mode.ACCEPT_EDITS && category == Category.WRITE) {
            return Decision.ALLOW;
        }
        return Decision.ASK;
    }

    private static Path resolveUserSettingsPath() {
        String override = System.getProperty("mewcode.userSettings");
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim()).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".mewcode", "settings.yaml");
    }

    private static Mode firstMode(String local, String project, String user) {
        Mode mode = Mode.parse(local);
        if (mode != null) {
            return mode;
        }
        mode = Mode.parse(project);
        if (mode != null) {
            return mode;
        }
        mode = Mode.parse(user);
        return mode == null ? Mode.DEFAULT : mode;
    }

    private String normalizedTarget(String friendly, String target) {
        if ("Bash".equalsIgnoreCase(friendly)) {
            return target == null ? "" : target;
        }
        if (target == null || target.trim().isEmpty()) {
            return ".";
        }
        Path resolvedRoot = Sandbox.resolveRoot(root);
        Path candidate = resolvedRoot.resolve(target).normalize();
        return resolvedRoot.relativize(candidate).toString().replace('\\', '/');
    }

    private String friendlyRuleText(String friendly, String normalizedTarget, Decision decision) {
        if (normalizedTarget == null || normalizedTarget.isEmpty() || ".".equals(normalizedTarget)) {
            return friendly + (decision == Decision.DENY ? " deny" : " allow");
        }
        return friendly + "(" + normalizedTarget + ")" + (decision == Decision.DENY ? " deny" : " allow");
    }

    private String buildAllowRule(ToolCall call) {
        String friendly = Settings.friendlyName(call.name());
        Settings.TargetInfo target = Settings.extractTarget(call);
        if (!target.ok()) {
            return null;
        }
        if ("Bash".equals(friendly)) {
            return friendly + "(" + escapePattern(target.target()) + ")";
        }
        return friendly + "(" + escapePattern(normalizedTarget(friendly, target.target())) + ")";
    }

    private String escapePattern(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '\\') {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    /**
     * 表示一次权限判定结果。
     */
    public static final class CheckResult {
        private final Decision decision;
        private final String reason;

        public CheckResult(Decision decision, String reason) {
            this.decision = decision;
            this.reason = reason == null ? "" : reason;
        }

        public Decision decision() {
            return decision;
        }

        public String reason() {
            return reason;
        }
    }
}
