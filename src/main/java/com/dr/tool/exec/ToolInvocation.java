package com.dr.tool.exec;

public record ToolInvocation(
        String toolCallId,
        String toolName,
        String argumentsJson
) {
}
