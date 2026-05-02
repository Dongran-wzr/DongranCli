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
        if (HIGH_RISK.contains(t)) {
            return new ApprovalDecision(true, RiskLevel.HIGH, "高风险命令执行工具");
        }
        if (MEDIUM_RISK.contains(t)) {
            return new ApprovalDecision(true, RiskLevel.MEDIUM, "中风险文件/项目写操作");
        }
        return ApprovalDecision.pass();
    }
}
