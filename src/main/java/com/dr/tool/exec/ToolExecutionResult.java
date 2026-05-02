package com.dr.tool.exec;

public record ToolExecutionResult(
        String toolCallId,
        String toolName,
        ToolExecutionStatus status,
        String result,
        String error,
        long durationMs
) {
    public String toToolMessageContent() {
        if (status == ToolExecutionStatus.SUCCESS) {
            return result == null ? "" : result;
        }
        String msg = error == null ? "" : error;
        return """
                {"ok":false,"code":"%s","message":"%s","tool":"%s","durationMs":%d}
                """.formatted(status.name().toLowerCase(), escape(msg), escape(toolName), durationMs).trim();
    }

    private String escape(String s) {
        return (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
