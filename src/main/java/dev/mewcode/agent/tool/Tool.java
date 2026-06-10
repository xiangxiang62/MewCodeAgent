package dev.mewcode.agent.tool;

import java.util.Map;

public interface Tool {
    String name();

    String description();

    Map<String, Object> parameters();

    boolean readOnly();

    Result execute(ToolContext context, String inputJson);
}
