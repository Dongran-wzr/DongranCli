package com.dr.tool.approval;

public interface ApprovalPolicy {
    ApprovalDecision evaluate(String toolName, String argumentsJson);
}
