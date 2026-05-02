package com.dr.team;

import com.dr.plan.PlanExecutionReport;

import java.util.ArrayList;
import java.util.List;

public record TeamExecutionResult(
        PlanExecutionReport report,
        List<AgentMessage> messages
) {
    public TeamExecutionResult {
        messages = messages == null ? List.of() : new ArrayList<>(messages);
    }
}
