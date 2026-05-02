package com.dr.tool;

import com.dr.audit.AuditLog;
import com.dr.tool.approval.ApprovalDecision;
import com.dr.tool.approval.ApprovalHandler;
import com.dr.tool.approval.ApprovalPolicy;
import com.dr.tool.approval.ApprovalRequest;
import com.dr.tool.approval.ApprovalResult;
import com.dr.tool.approval.ConsoleApprovalHandler;
import com.dr.tool.approval.StaticApprovalPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ApprovalAwareToolRegistry extends ToolRegistry {
    private static final Object DISPLAY_LOCK = new Object();
    private static final ConcurrentHashMap<String, Boolean> SESSION_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean allowAllInSession = false;

    private final ApprovalPolicy approvalPolicy;
    private final ApprovalHandler approvalHandler;
    private final AuditLog auditLog;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean hitlEnabled;

    public ApprovalAwareToolRegistry(
            Path workspaceRoot,
            ApprovalPolicy approvalPolicy,
            ApprovalHandler approvalHandler,
            boolean hitlEnabled
    ) {
        super(workspaceRoot);
        this.approvalPolicy = approvalPolicy;
        this.approvalHandler = approvalHandler;
        this.hitlEnabled = hitlEnabled;
        this.auditLog = new AuditLog(workspaceRoot);
    }

    public static ApprovalAwareToolRegistry createDefault(Path workspaceRoot) {
        boolean enabled = !"false".equalsIgnoreCase(System.getenv("HITL_ENABLED"));
        return new ApprovalAwareToolRegistry(
                workspaceRoot,
                new StaticApprovalPolicy(),
                new ConsoleApprovalHandler(),
                enabled
        );
    }

    @Override
    public String executeTool(String toolName, String argumentsJson) {
        if (!hitlEnabled) {
            auditLog.log("hitl", "bypass", toolName);
            return super.executeTool(toolName, argumentsJson); // HITL 关闭时零开销路径
        }

        ApprovalDecision decision = approvalPolicy.evaluate(toolName, argumentsJson);
        if (!decision.requiresApproval()) {
            return super.executeTool(toolName, argumentsJson);
        }
        if (allowAllInSession) {
            return super.executeTool(toolName, argumentsJson);
        }

        String fingerprint = fingerprint(toolName, argumentsJson);
        if (Boolean.TRUE.equals(SESSION_CACHE.get(fingerprint))) {
            return super.executeTool(toolName, argumentsJson);
        }

        synchronized (DISPLAY_LOCK) { // 多 Agent 并发下串行展示审批
            if (allowAllInSession || Boolean.TRUE.equals(SESSION_CACHE.get(fingerprint))) {
                return super.executeTool(toolName, argumentsJson);
            }
            try {
                ApprovalRequest req = new ApprovalRequest(
                        UUID.randomUUID().toString(),
                        toolName,
                        decision.riskLevel(),
                        decision.reason(),
                        argumentsJson,
                        Instant.now()
                );
                ApprovalResult result = approvalHandler.request(req);
                if (result.allowAllInSession()) {
                    allowAllInSession = true;
                }
                if (!result.approved()) {
                    auditLog.log("hitl", "deny", toolName);
                    return deny("approval_denied", "工具调用被人工审批拒绝: " + toolName);
                }
                SESSION_CACHE.put(fingerprint, true);
                auditLog.log("hitl", "approve", toolName);
                return super.executeTool(toolName, argumentsJson);
            } catch (Exception e) {
                auditLog.log("hitl", "error", toolName + " -> " + e.getMessage());
                return deny("approval_failed", "审批异常，按 fail-safe 拒绝: " + e.getMessage());
            }
        }
    }

    public boolean isHitlEnabled() {
        return hitlEnabled;
    }

    public void setHitlEnabled(boolean enabled) {
        this.hitlEnabled = enabled;
    }

    public void resetSessionApprovals() {
        SESSION_CACHE.clear();
        allowAllInSession = false;
    }

    private String fingerprint(String toolName, String args) {
        String base = (toolName == null ? "" : toolName) + "|" + (args == null ? "" : args);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(base.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(base.hashCode());
        }
    }

    private String deny(String code, String message) {
        try {
            return mapper.writeValueAsString(Map.of(
                    "ok", false,
                    "code", code,
                    "message", message
            ));
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":\"approval_error\"}";
        }
    }
}
