package dev.mewcode.agent.mcp;

import dev.mewcode.agent.mcp.McpConfig.ServerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 配置加载器测试。
 */
public class ConfigLoaderTest {
    @Test
    public void mergeServersShouldPreferProjectLevel() {
        Map<String, ConfigLoader.RawServer> user = new LinkedHashMap<String, ConfigLoader.RawServer>();
        Map<String, ConfigLoader.RawServer> project = new LinkedHashMap<String, ConfigLoader.RawServer>();
        user.put("demo", new ConfigLoader.RawServer("stdio", "user-cmd", Collections.singletonList("a"),
                Collections.<String, String>emptyMap(), null, Collections.<String, String>emptyMap()));
        project.put("demo", new ConfigLoader.RawServer("stdio", "project-cmd", Collections.singletonList("b"),
                Collections.<String, String>emptyMap(), null, Collections.<String, String>emptyMap()));

        Map<String, ConfigLoader.RawServer> merged = ConfigLoader.mergeServers(user, project);
        Assert.assertEquals("project-cmd", merged.get("demo").command());
    }

    @Test
    public void expandVarsShouldReplaceUndefinedWithEmptyString() {
        ConfigLoader.Expansion expansion = ConfigLoader.expandVars("Bearer ${MEWCODE_NOT_DEFINED_TEST_VAR}");
        Assert.assertEquals("Bearer ", expansion.out());
        Assert.assertEquals(1, expansion.undefined().size());
    }

    @Test
    public void validateServerShouldRejectInvalidType() {
        Optional<ServerConfig> validated = ConfigLoader.validateServer("bad",
                new ConfigLoader.RawServer("rpc", null, Collections.<String>emptyList(),
                        Collections.<String, String>emptyMap(), null, Collections.<String, String>emptyMap()));
        Assert.assertFalse(validated.isPresent());
    }

    @Test
    public void loadConfigShouldIgnoreInvalidYamlFile() throws Exception {
        Path root = Files.createTempDirectory("mcp-config-invalid");
        Files.write(root.resolve(".mewcode.yaml"), "mcp_servers: [".getBytes(StandardCharsets.UTF_8));

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try {
            System.setErr(new PrintStream(err, true, "UTF-8"));
            McpConfig config = ConfigLoader.loadConfig(root);
            Assert.assertTrue(config.servers().isEmpty());
        } finally {
            System.setErr(previous);
        }
        Assert.assertTrue(err.toString("UTF-8").contains("[mcp] warn"));
    }

    @Test
    public void loadConfigShouldReadProjectLevelMcpServers() throws Exception {
        Path root = Files.createTempDirectory("mcp-config-project");
        String yaml = "mcp_servers:\n"
                + "  demo:\n"
                + "    type: stdio\n"
                + "    command: npx\n"
                + "    args: ['-y', 'server']\n";
        Files.write(root.resolve(".mewcode.yaml"), yaml.getBytes(StandardCharsets.UTF_8));

        McpConfig config = ConfigLoader.loadConfig(root);
        Assert.assertEquals(1, config.servers().size());
        Assert.assertEquals("npx", config.servers().get("demo").command());
    }
}
