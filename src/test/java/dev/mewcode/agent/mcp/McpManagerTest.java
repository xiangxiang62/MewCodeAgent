package dev.mewcode.agent.mcp;

import dev.mewcode.agent.mcp.McpConfig.ServerConfig;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MCP 管理器测试。
 */
public class McpManagerTest {
    @Test
    public void startShouldHandleEmptyConfig() {
        McpManager manager = McpManager.start(new McpConfig(Collections.<String, ServerConfig>emptyMap()), "test");
        Assert.assertTrue(manager.tools().isEmpty());
        manager.close();
    }

    @Test
    public void startShouldSkipBrokenServer() {
        Map<String, ServerConfig> servers = new LinkedHashMap<String, ServerConfig>();
        servers.put("broken", new ServerConfig("stdio", "command-that-does-not-exist-123456",
                Collections.<String>emptyList(), Collections.<String, String>emptyMap(), null,
                Collections.<String, String>emptyMap()));

        McpManager manager = McpManager.start(new McpConfig(servers), "test");
        Assert.assertTrue(manager.tools().isEmpty());
        manager.close();
    }

    @Test
    public void mergeOsEnvShouldAllowOverrides() {
        Map<String, String> extra = new LinkedHashMap<String, String>();
        extra.put("MEWCODE_MCP_TEST_ENV", "value");

        Map<String, String> merged = McpManager.mergeOsEnv(extra);
        Assert.assertEquals("value", merged.get("MEWCODE_MCP_TEST_ENV"));
    }
}
