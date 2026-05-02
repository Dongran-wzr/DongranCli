package com.dr.tool.approval;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record ApprovalRequest(
        String requestId,
        String toolName,
        RiskLevel riskLevel,
        String reason,
        String argumentsJson,
        Instant createdAt
) {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public String toDisplayText() {
        String title = "人工审批请求 (HITL)";
        String t1 = "RequestId : " + value(requestId);
        String t2 = "Tool      : " + value(toolName);
        String t3 = "Risk      : " + riskLevel;
        String t4 = "Reason    : " + value(reason);
        String t5 = "Time      : " + TS.format(createdAt == null ? Instant.now() : createdAt);
        String args = "Args      : " + compactArgs(argumentsJson);

        int width = max(DisplayWidth.width(title), DisplayWidth.width(t1), DisplayWidth.width(t2),
                DisplayWidth.width(t3), DisplayWidth.width(t4), DisplayWidth.width(t5), DisplayWidth.width(args));
        width = Math.max(width, 48);
        String border = "+" + "-".repeat(width + 2) + "+";
        StringBuilder sb = new StringBuilder();
        sb.append(border).append("\n");
        sb.append("| ").append(DisplayWidth.padRight(title, width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight("", width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight(t1, width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight(t2, width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight(t3, width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight(t4, width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight(t5, width)).append(" |\n");
        sb.append("| ").append(DisplayWidth.padRight(args, width)).append(" |\n");
        sb.append(border);
        return sb.toString();
    }

    private String compactArgs(String s) {
        String v = value(s).replaceAll("\\s+", " ");
        return v.length() <= 120 ? v : v.substring(0, 120) + "...";
    }

    private int max(int... values) {
        int m = values[0];
        for (int v : values) {
            m = Math.max(m, v);
        }
        return m;
    }

    private String value(String s) {
        return s == null ? "" : s;
    }
}
