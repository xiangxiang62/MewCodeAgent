package dev.mewcode.agent.tool;

import dev.mewcode.agent.llm.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class Registry {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final List<String> order = new ArrayList<>();
    private final Map<String, Tool> tools = new HashMap<>();

    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为空");
        }
        if (!tools.containsKey(tool.name())) {
            order.add(tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<ToolDefinition> definitions() {
        List<ToolDefinition> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            definitions.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
        }
        return definitions;
    }

    public Result execute(ToolContext context, String name, String args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return Result.error("未知工具: " + name);
        }
        try {
            return tool.execute(context, args == null || args.trim().isEmpty() ? "{}" : args);
        } catch (Exception e) {
            return Result.error("工具执行失败: " + e.getMessage());
        }
    }

    public static Registry defaultRegistry() {
        Registry registry = new Registry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        return registry;
    }
}
