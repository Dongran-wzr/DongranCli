package com.dr.tool.approval;

import java.util.Locale;
import java.util.Set;

public class StaticApprovalPolicy implements ApprovalPolicy {
    private static final Set<String> HIGH_RISK = Set.of(
            "execute_command",
            "run_command"
    );
    private static final Set<String> MEDIUM_RISK = Set.of(
            "write_file",
            "create_project"
    );

    @Override
    public ApprovalDecision evaluate(String toolName, String argumentsJson) {
        String t = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT).trim();
        String mode = System.getProperty("DONGRAN_SECURITY_MODE", "balanced").toLowerCase(Locale.ROOT);
        if ("permissive".equals(mode)) {
            return ApprovalDecision.pass();
        }
        if (HIGH_RISK.contains(t)) {
            if ("balanced".equals(mode) && "run_command".equals(t) && isKnownSafeRunCommand(argumentsJson)) {
                return ApprovalDecision.pass();
            }
            return new ApprovalDecision(true, RiskLevel.HIGH, "高风险命令执行工具");
        }
        if (MEDIUM_RISK.contains(t)) {
            return new ApprovalDecision(true, RiskLevel.MEDIUM, "中风险文件/项目写操作");
        }
        if (t.startsWith("mcp__")) {
            return new ApprovalDecision(true, RiskLevel.MEDIUM, "第三方 MCP 工具默认纳入人工审批");
        }
        return ApprovalDecision.pass();
    }

    private boolean looksDangerous(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return false;
        }
        String v = argumentsJson.toLowerCase(Locale.ROOT);
        return v.contains("rm -rf")
                || v.contains("remove-item -recurse -force")
                || v.contains("del /f /s /q")
                || v.contains("format ")
                || v.contains("diskpart")
                || v.contains("shutdown")
                || v.contains("reboot")
                || v.contains("mkfs");
    }

    private boolean isKnownSafeRunCommand(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return false;
        }
        String v = argumentsJson.toLowerCase(Locale.ROOT);
        if (!v.contains("\"command\"")) {
            return false;
        }
        return v.contains("git status")
                || v.contains("git diff")
                || v.contains("java -version")
                || v.contains("mvn ")
                || v.contains("npm ")
                || v.contains("npx ")
                || v.contains("node ");
    }
}
