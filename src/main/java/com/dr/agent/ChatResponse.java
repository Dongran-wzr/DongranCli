package com.dr.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChatResponse {
    private final String content;
    private final List<ToolCall> toolCalls;

    public ChatResponse(String content, List<ToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null ? List.of() : new ArrayList<>(toolCalls);
    }

    public String content() {
        return content;
    }

    public List<ToolCall> toolCalls() {
        return Collections.unmodifiableList(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
