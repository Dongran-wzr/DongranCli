package com.dr.plan;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class PlanExecutionReport {
    private final String objective;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final List<TaskSnapshot> tasks;
    private final String finalAnswer;

    public record TaskSnapshot(
            String id,
            String description,
            List<String> dependencies,
            TaskStatus status,
            int attempts,
            long durationMs,
            String output,
            String error
    ) {
    }

    public PlanExecutionReport(
            String objective,
            Instant startedAt,
            Instant finishedAt,
            List<TaskSnapshot> tasks,
            String finalAnswer
    ) {
        this.objective = objective;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.tasks = tasks == null ? List.of() : new ArrayList<>(tasks);
        this.finalAnswer = finalAnswer == null ? "" : finalAnswer;
    }

    public String objective() {
        return objective;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public List<TaskSnapshot> tasks() {
        return List.copyOf(tasks);
    }

    public String finalAnswer() {
        return finalAnswer;
    }

    public String toHumanReadable() {
        StringBuilder sb = new StringBuilder();
        sb.append("目标: ").append(objective).append("\n");
        sb.append("开始: ").append(startedAt).append("\n");
        sb.append("结束: ").append(finishedAt).append("\n");
        sb.append("任务状态:\n");
        for (TaskSnapshot t : tasks) {
            sb.append("- ").append(t.id()).append(" [").append(t.status()).append("] ")
                    .append(t.description())
                    .append(" | attempts=").append(t.attempts())
                    .append(" | durationMs=").append(t.durationMs())
                    .append("\n");
            if (t.error() != null && !t.error().isBlank()) {
                sb.append("  error: ").append(t.error()).append("\n");
            }
        }
        return sb.toString();
    }
}
