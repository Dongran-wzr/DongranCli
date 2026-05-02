package com.dr.team;

import java.time.Instant;

public record AgentMessage(
        AgentMessageType type,
        AgentRole fromRole,
        AgentRole toRole,
        String taskId,
        String content,
        Instant timestamp
) {
    public static AgentMessage of(
            AgentMessageType type,
            AgentRole fromRole,
            AgentRole toRole,
            String taskId,
            String content
    ) {
        return new AgentMessage(type, fromRole, toRole, taskId, content, Instant.now());
    }
}
