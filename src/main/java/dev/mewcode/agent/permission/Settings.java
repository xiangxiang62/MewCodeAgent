package dev.mewcode.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.mewcode.agent.llm.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * 表示单个权限配置文件的内容。
 */
public final class Settings {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String defaultMode;
    private final List<String> allow;
    private final List<String> deny;

    public Settings(String defaultMode, List<String> allow, List<String> deny) {
        this.defaultMode = defaultMode;
        this.allow = Collections.unmodifiableList(new ArrayList<String>(allow));
        this.deny = Collections.unmodifiableList(new ArrayList<String>(deny));
    }

    public static Settings empty() {
        return new Settings(null, Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    public String defaultMode() {
        return defaultMode;
    }

    public List<String> allow() {
        return allow;
    }

    public List<String> deny() {
        return deny;
    }

    /**
     * 安全加载单个配置文件。缺失或格式错误都降级为空配置。
     */
    public static Settings load(Path path) {
        if (path == null || !Files.exists(path)) {
            return empty();
        }
        try {
            JsonNode root = YAML.readTree(Files.newBufferedReader(path));
            if (root == null || root.isMissingNode()) {
                return empty();
            }
            String defaultMode = text(root.get("defaultMode"));
            JsonNode permissions = root.get("permissions");
            List<String> allow = strings(permissions == null ? null : permissions.get("allow"));
            List<String> deny = strings(permissions == null ? null : permissions.get("deny"));
            return new Settings(defaultMode, allow, deny);
        } catch (Exception e) {
            return empty();
        }
    }

    /**
     * 把配置转换成规则集合，非法规则自动跳过。
     */
    public RuleSet toRuleSet() {
        List<Rule> allowRules = new ArrayList<Rule>();
        List<Rule> denyRules = new ArrayList<Rule>();
        for (String item : allow) {
            Rule rule = Rule.parse(item, true);
            if (rule != null) {
                allowRules.add(rule);
            }
        }
        for (String item : deny) {
            Rule rule = Rule.parse(item, false);
            if (rule != null) {
                denyRules.add(rule);
            }
        }
        return new RuleSet(allowRules, denyRules);
    }

    /**
     * 把内部工具名映射成友好名。
     */
    public static String friendlyName(String internalName) {
        if ("bash".equals(internalName)) {
            return "Bash";
        }
        if ("read_file".equals(internalName)) {
            return "Read";
        }
        if ("write_file".equals(internalName)) {
            return "Write";
        }
        if ("edit_file".equals(internalName)) {
            return "Edit";
        }
        if ("glob".equals(internalName)) {
            return "Glob";
        }
        if ("grep".equals(internalName)) {
            return "Grep";
        }
        return internalName == null ? "" : internalName;
    }

    /**
     * 根据工具名与只读标记推断权限类别。
     */
    public static Category categorize(String internalName, boolean readOnly) {
        if (readOnly) {
            return Category.READ;
        }
        if ("write_file".equals(internalName) || "edit_file".equals(internalName)) {
            return Category.WRITE;
        }
        return Category.EXEC;
    }

    /**
     * 从工具参数中提取权限判定目标。
     */
    public static TargetInfo extractTarget(ToolCall call) {
        if (call == null || call.name() == null) {
            return new TargetInfo("", false, false);
        }
        try {
            JsonNode root = JSON.readTree(call.inputJson());
            if ("read_file".equals(call.name())
                    || "write_file".equals(call.name())
                    || "edit_file".equals(call.name())) {
                JsonNode path = root.get("path");
                return path != null && path.isTextual()
                        ? new TargetInfo(path.asText(), true, true)
                        : new TargetInfo("", true, false);
            }
            if ("glob".equals(call.name()) || "grep".equals(call.name())) {
                JsonNode path = root.get("path");
                return new TargetInfo(path != null && path.isTextual() ? path.asText() : ".", true, true);
            }
            if ("bash".equals(call.name())) {
                JsonNode command = root.get("command");
                return command != null && command.isTextual()
                        ? new TargetInfo(command.asText(), false, true)
                        : new TargetInfo("", false, false);
            }
        } catch (IOException e) {
            if ("bash".equals(call.name())) {
                return new TargetInfo("", false, false);
            }
            return new TargetInfo("", true, false);
        }
        return new TargetInfo("", false, false);
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<String>();
        Iterator<JsonNode> iterator = node.elements();
        while (iterator.hasNext()) {
            JsonNode item = iterator.next();
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    /**
     * 承载权限判定所需的目标信息。
     */
    public static final class TargetInfo {
        private final String target;
        private final boolean file;
        private final boolean ok;

        public TargetInfo(String target, boolean file, boolean ok) {
            this.target = target == null ? "" : target;
            this.file = file;
            this.ok = ok;
        }

        public String target() {
            return target;
        }

        public boolean isFile() {
            return file;
        }

        public boolean ok() {
            return ok;
        }
    }
}
