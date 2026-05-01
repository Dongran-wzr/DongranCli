package com.dr.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class Message {
    private String role;
    private String content;
    private String toolCallId;
    private List<ToolCall> toolCalls;

    public Message() {
    }

    public Message(String role, String content, String toolCallId, List<ToolCall> toolCalls) {
        this.role = role;
        this.content = content;
        this.toolCallId = toolCallId;
        this.toolCalls = toolCalls;
    }

    public static Message system(String content) {
        return new Message("system", content, null, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null, null);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content, null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, null, new ArrayList<>(toolCalls));
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, toolCallId, null);
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }
}
