package dev.mewcode.agent.permission;

import dev.mewcode.agent.llm.ToolCall;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 覆盖权限引擎的关键安全行为，避免回归。
 */
public class PermissionEngineTest {
    static {
        System.setProperty("mewcode.userSettings", "__tests__/missing-user-settings.yaml");
    }

    @Test
    public void defaultModeShouldAskForWriteInsideWorkspace() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-default");
        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult result = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "write_file", "{\"path\":\"note.txt\",\"content\":\"ok\"}"),
                false);

        assertEquals(Decision.ASK, result.decision());
        assertTrue(result.reason().contains("default"));
    }

    @Test
    public void defaultModeShouldAllowReadInsideWorkspace() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-read");
        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult result = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "read_file", "{\"path\":\"note.txt\"}"),
                true);

        assertEquals(Decision.ALLOW, result.decision());
    }

    @Test
    public void blacklistShouldDenyDangerousCommandInBypassMode() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-bypass");
        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult result = engine.check(
                Mode.BYPASS,
                new ToolCall("c1", "bash", "{\"command\":\"rm -rf /\"}"),
                false);

        assertEquals(Decision.DENY, result.decision());
        assertTrue(result.reason().contains("黑名单"));
    }

    @Test
    public void sandboxShouldDenyOutsideWorkspaceAndAllowNestedNewFile() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-sandbox");
        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult outside = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "write_file", "{\"path\":\"..\\\\outside.txt\",\"content\":\"x\"}"),
                false);
        PermissionEngine.CheckResult nested = engine.check(
                Mode.DEFAULT,
                new ToolCall("c2", "write_file", "{\"path\":\"src\\\\main\\\\App.java\",\"content\":\"x\"}"),
                false);

        assertEquals(Decision.DENY, outside.decision());
        assertTrue(outside.reason().contains("项目目录之外"));
        assertEquals(Decision.ASK, nested.decision());
    }

    @Test
    public void projectRulesShouldSupportExactAndGlobMatch() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-rules");
        writeProjectSettings(root,
                "defaultMode: default\n"
                        + "permissions:\n"
                        + "  allow:\n"
                        + "    - \"Bash(git status)\"\n"
                        + "    - \"Write(src/**)\"\n");

        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult gitStatus = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "bash", "{\"command\":\"git status\"}"),
                false);
        PermissionEngine.CheckResult gitPush = engine.check(
                Mode.DEFAULT,
                new ToolCall("c2", "bash", "{\"command\":\"git push\"}"),
                false);
        PermissionEngine.CheckResult writeSrc = engine.check(
                Mode.DEFAULT,
                new ToolCall("c3", "write_file", "{\"path\":\"src/a/b.java\",\"content\":\"x\"}"),
                false);
        PermissionEngine.CheckResult writeDocs = engine.check(
                Mode.DEFAULT,
                new ToolCall("c4", "write_file", "{\"path\":\"docs/x.md\",\"content\":\"x\"}"),
                false);

        assertEquals(Decision.ALLOW, gitStatus.decision());
        assertEquals(Decision.ASK, gitPush.decision());
        assertEquals(Decision.ALLOW, writeSrc.decision());
        assertEquals(Decision.ASK, writeDocs.decision());
    }

    @Test
    public void denyShouldWinWithinSameRuleLayer() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-deny");
        writeProjectSettings(root,
                "permissions:\n"
                        + "  allow:\n"
                        + "    - \"Write(src/**)\"\n"
                        + "  deny:\n"
                        + "    - \"Write(src/secret.txt)\"\n");

        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult result = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "write_file", "{\"path\":\"src/secret.txt\",\"content\":\"x\"}"),
                false);

        assertEquals(Decision.DENY, result.decision());
    }

    @Test
    public void localRulesShouldOverrideProjectAndUserRules() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-precedence");
        Path userSettings = root.resolve("user-settings.yaml");
        System.setProperty("mewcode.userSettings", userSettings.toString());

        Files.write(userSettings, Arrays.asList(
                "permissions:",
                "  allow:",
                "    - \"Write(src/**)\""), StandardCharsets.UTF_8);
        writeProjectSettings(root,
                "permissions:\n"
                        + "  allow:\n"
                        + "    - \"Write(src/**)\"\n");
        writeLocalSettings(root,
                "permissions:\n"
                        + "  deny:\n"
                        + "    - \"Write(src/blocked.txt)\"\n");

        PermissionEngine engine = PermissionEngine.create(root);
        PermissionEngine.CheckResult result = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "write_file", "{\"path\":\"src/blocked.txt\",\"content\":\"x\"}"),
                false);

        assertEquals(Decision.DENY, result.decision());

        System.setProperty("mewcode.userSettings", "__tests__/missing-user-settings.yaml");
    }

    @Test
    public void allowForeverShouldPersistAndReload() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-persist");
        PermissionEngine engine = PermissionEngine.create(root);
        ToolCall call = new ToolCall("c1", "write_file", "{\"path\":\"notes/todo.md\",\"content\":\"x\"}");

        engine.persistLocalAllow(call);

        Path localSettings = root.resolve(".mewcode").resolve("settings.local.yaml");
        String content = new String(Files.readAllBytes(localSettings), StandardCharsets.UTF_8);
        assertTrue(content.contains("Write(notes/todo.md)"));

        PermissionEngine reloaded = PermissionEngine.create(root);
        PermissionEngine.CheckResult result = reloaded.check(Mode.DEFAULT, call, false);
        assertEquals(Decision.ALLOW, result.decision());
    }

    @Test
    public void invalidJsonFileCallShouldBeDenied() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-invalid-json");
        PermissionEngine engine = PermissionEngine.create(root);

        PermissionEngine.CheckResult result = engine.check(
                Mode.DEFAULT,
                new ToolCall("c1", "write_file", "{not-json"),
                false);

        assertEquals(Decision.DENY, result.decision());
    }

    @Test
    public void localProjectUserDefaultModeShouldUseNearestLayer() throws Exception {
        Path root = Files.createTempDirectory("mewcode-permission-mode");
        Path userSettings = root.resolve("user-settings.yaml");
        System.setProperty("mewcode.userSettings", userSettings.toString());

        Files.write(userSettings, Arrays.asList("defaultMode: plan"), StandardCharsets.UTF_8);
        writeProjectSettings(root, "defaultMode: acceptEdits\n");
        writeLocalSettings(root, "defaultMode: bypassPermissions\n");

        PermissionEngine engine = PermissionEngine.create(root);
        assertEquals(Mode.BYPASS, engine.startMode());

        System.setProperty("mewcode.userSettings", "__tests__/missing-user-settings.yaml");
    }

    private void writeProjectSettings(Path root, String yaml) throws Exception {
        Path dir = root.resolve(".mewcode");
        Files.createDirectories(dir);
        Files.write(dir.resolve("settings.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }

    private void writeLocalSettings(Path root, String yaml) throws Exception {
        Path dir = root.resolve(".mewcode");
        Files.createDirectories(dir);
        Files.write(dir.resolve("settings.local.yaml"), yaml.getBytes(StandardCharsets.UTF_8));
    }
}
