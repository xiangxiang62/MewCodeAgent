package dev.mewcode.agent.tool;

import java.util.Map;

/**
 * 统一描述一个可由模型调用的本地工具。
 */
public interface Tool {
    /**
     * 返回工具在协议中的唯一名称。
     */
    String name();

    /**
     * 返回给模型阅读的工具用途说明。
     */
    String description();

    /**
     * 返回工具参数的 JSON Schema 描述。
     */
    Map<String, Object> parameters();

    /**
     * 标记该工具是否为只读工具。
     */
    boolean readOnly();

    /**
     * 执行工具并返回结构化结果。
     */
    Result execute(ToolContext context, String inputJson);
}
