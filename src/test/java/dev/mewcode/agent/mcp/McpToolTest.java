package dev.mewcode.agent.mcp;

import dev.mewcode.agent.tool.Result;
import dev.mewcode.agent.tool.ToolContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MCP 工具适配测试。
 */
public class McpToolTest {
    @Test
    public void adaptToolShouldBuildNamespacedTool() {
        McpSchema.Tool tool = new McpSchema.Tool("echo", "desc", defaultSchema());
        Optional<McpTool> adapted = McpTool.adaptTool("demo", tool, (name, arguments) -> null);
        Assert.assertTrue(adapted.isPresent());
        Assert.assertEquals("mcp__demo__echo", adapted.get().name());
    }

    @Test
    public void adaptToolShouldRejectIllegalName() {
        McpSchema.Tool tool = new McpSchema.Tool("echo.test", "desc", defaultSchema());
        Optional<McpTool> adapted = McpTool.adaptTool("demo", tool, (name, arguments) -> null);
        Assert.assertFalse(adapted.isPresent());
    }

    @Test
    public void executeShouldPassArgumentsAndCollectText() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<Map<String, Object>>();
        McpSchema.Tool tool = new McpSchema.Tool("echo", "desc", defaultSchema());
        McpTool adapted = McpTool.adaptTool("demo", tool, (name, arguments) -> {
            captured.set(arguments);
            return new McpSchema.CallToolResult(Arrays.asList(
                    new McpSchema.TextContent("line1"),
                    new McpSchema.TextContent("line2")), false);
        }).get();

        Result result = adapted.execute(ToolContext.fresh(), "{\"text\":\"hello\"}");
        Assert.assertFalse(result.isError());
        Assert.assertEquals("line1\nline2", result.content());
        Assert.assertEquals("hello", captured.get().get("text"));
    }

    @Test
    public void executeShouldMapRemoteError() {
        McpSchema.Tool tool = new McpSchema.Tool("echo", "desc", defaultSchema());
        McpTool adapted = McpTool.adaptTool("demo", tool, (name, arguments) -> new McpSchema.CallToolResult(
                Collections.singletonList(new McpSchema.TextContent("failed")), true)).get();

        Result result = adapted.execute(ToolContext.fresh(), "{}");
        Assert.assertTrue(result.isError());
        Assert.assertEquals("failed", result.content());
    }

    @Test
    public void executeShouldHandleProtocolException() {
        McpSchema.Tool tool = new McpSchema.Tool("echo", "desc", defaultSchema());
        McpTool adapted = McpTool.adaptTool("demo", tool, (name, arguments) -> {
            throw new IllegalStateException("boom");
        }).get();

        Result result = adapted.execute(ToolContext.fresh(), "{}");
        Assert.assertTrue(result.isError());
        Assert.assertTrue(result.content().contains("boom"));
    }

    private McpSchema.JsonSchema defaultSchema() {
        return new McpSchema.JsonSchema("object", new LinkedHashMap<String, Object>(),
                Collections.<String>emptyList(), false, null, null);
    }
}
