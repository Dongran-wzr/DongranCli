package com.dr.plan;

import java.util.ArrayList;
import java.util.List;

public class Task {
    private final String id;
    private final String description;
    private final List<String> dependencies;
    private TaskStatus status;
    private String output;
    private String error;
    private int attempts;
    private long durationMs;

    public Task(String id, String description, List<String> dependencies) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("task id 不能为空");
        }
        this.id = id.trim();
        this.description = description == null ? "" : description.trim();
        this.dependencies = dependencies == null ? List.of() : new ArrayList<>(dependencies);
        this.status = TaskStatus.PENDING;
        this.attempts = 0;
        this.durationMs = 0L;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public List<String> dependencies() {
        return List.copyOf(dependencies);
    }

    public TaskStatus status() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public String output() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public String error() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public int attempts() {
        return attempts;
    }

    public void setAttempts(int attempts) {
        this.attempts = Math.max(0, attempts);
    }

    public long durationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = Math.max(0L, durationMs);
    }
}
