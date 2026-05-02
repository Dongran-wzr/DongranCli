package com.dr.tool.approval;

import java.util.Locale;
import java.util.Scanner;

public class ConsoleApprovalHandler implements ApprovalHandler {
    private final Scanner scanner;

    public ConsoleApprovalHandler() {
        this.scanner = new Scanner(System.in);
    }

    @Override
    public ApprovalResult request(ApprovalRequest approvalRequest) {
        System.out.println();
        System.out.println(approvalRequest.toDisplayText());
        System.out.println("输入 y 放行, n 拒绝, a 放行并本会话全部自动通过:");
        String in = scanner.nextLine().trim().toLowerCase(Locale.ROOT);
        return switch (in) {
            case "y", "yes" -> new ApprovalResult(true, false);
            case "a", "all" -> new ApprovalResult(true, true);
            default -> ApprovalResult.deny();
        };
    }
}
