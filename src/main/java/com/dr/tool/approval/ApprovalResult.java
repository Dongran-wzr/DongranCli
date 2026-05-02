package com.dr.tool.approval;

public record ApprovalResult(boolean approved, boolean allowAllInSession) {
    public static ApprovalResult deny() {
        return new ApprovalResult(false, false);
    }
}
