package dev.mewcode.agent.tool;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> parameters();

    Result execute(ToolContext context, String inputJson);
}
