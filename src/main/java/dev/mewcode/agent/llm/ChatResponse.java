package dev.mewcode.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ChatResponse {
    private final String text;
    private final List<ToolCall> toolCalls;

    public ChatResponse(String text, List<ToolCall> toolCalls) {
        this.text = text == null ? "" : text;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }

    public String text() {
        return text;
    }

    public List<ToolCall> toolCalls() {
        return toolCalls;
    }
}
