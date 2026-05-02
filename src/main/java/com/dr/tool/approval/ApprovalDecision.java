package com.dr.tool.approval;

public record ApprovalDecision(
        boolean requiresApproval,
        RiskLevel riskLevel,
        String reason
) {
    public static ApprovalDecision pass() {
        return new ApprovalDecision(false, RiskLevel.LOW, "");
    }
}
