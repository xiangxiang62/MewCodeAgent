package dev.mewcode.agent.tool;

import dev.mewcode.agent.llm.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 负责注册工具、导出工具定义，以及按名称执行工具。
 */
public final class Registry {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final List<String> order = new ArrayList<String>();
    private final Map<String, Tool> tools = new HashMap<String, Tool>();

    /**
     * 注册一个工具，并保留首次注册时的显示顺序。
     */
    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为空");
        }
        synchronized (this) {
            if (!tools.containsKey(tool.name())) {
                order.add(tool.name());
            }
            tools.put(tool.name(), tool);
        }
    }

    /**
     * 按名称查找工具。
     */
    public Optional<Tool> get(String name) {
        synchronized (this) {
            return Optional.ofNullable(tools.get(name));
        }
    }

    /**
     * 导出当前全部工具的协议定义，供模型挑选调用。
     */
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        synchronized (this) {
            for (String name : order) {
                Tool tool = tools.get(name);
                definitions.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
            }
        }
        return definitions;
    }

    /**
     * 仅导出只读工具定义，用于计划模式。
     */
    public List<ToolDefinition> readOnlyDefinitions() {
        List<ToolDefinition> definitions = new ArrayList<ToolDefinition>();
        synchronized (this) {
            for (String name : order) {
                Tool tool = tools.get(name);
                if (tool.readOnly()) {
                    definitions.add(new ToolDefinition(tool.name(), tool.description(), tool.parameters()));
                }
            }
        }
        return definitions;
    }

    /**
     * 判断指定工具是否为只读工具。
     */
    public boolean isReadOnly(String name) {
        synchronized (this) {
            Tool tool = tools.get(name);
            return tool != null && tool.readOnly();
        }
    }

    /**
     * 执行指定工具。
     * 当参数为空时，自动补成空 JSON，避免每个工具重复兜底。
     */
    public Result execute(ToolContext context, String name, String args) {
        Tool tool;
        synchronized (this) {
            tool = tools.get(name);
        }
        if (tool == null) {
            return Result.error("未知工具: " + name);
        }
        try {
            return tool.execute(context, args == null || args.trim().isEmpty() ? "{}" : args);
        } catch (Exception e) {
            return Result.error("工具执行失败: " + e.getMessage());
        }
    }

    /**
     * 创建内置工具集合。
     */
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
