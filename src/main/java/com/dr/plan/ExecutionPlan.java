package com.dr.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ExecutionPlan {
    private final String objective;
    private final Map<String, Task> taskMap;

    public ExecutionPlan(String objective, List<Task> tasks) {
        this.objective = objective == null ? "" : objective;
        this.taskMap = new HashMap<>();
        for (Task task : tasks) {
            if (taskMap.containsKey(task.id())) {
                throw new IllegalArgumentException("任务 ID 重复: " + task.id());
            }
            taskMap.put(task.id(), task);
        }
        validateDependencies();
        detectCycle();
    }

    public String objective() {
        return objective;
    }

    public Collection<Task> tasks() {
        return taskMap.values();
    }

    public List<Task> tasksSorted() {
        List<Task> list = new ArrayList<>(taskMap.values());
        list.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return list;
    }

    public Task getTask(String id) {
        return taskMap.get(id);
    }

    public List<Task> getReadyTasks() {
        List<Task> ready = new ArrayList<>();
        for (Task task : taskMap.values()) {
            if (task.status() != TaskStatus.PENDING) {
                continue;
            }
            boolean depsDone = true;
            for (String dep : task.dependencies()) {
                Task depTask = taskMap.get(dep);
                if (depTask == null || depTask.status() != TaskStatus.SUCCEEDED) {
                    depsDone = false;
                    break;
                }
            }
            if (depsDone) {
                ready.add(task);
            }
        }
        ready.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return ready;
    }

    public boolean allFinished() {
        for (Task task : taskMap.values()) {
            if (task.status() == TaskStatus.PENDING || task.status() == TaskStatus.RUNNING) {
                return false;
            }
        }
        return true;
    }

    private void validateDependencies() {
        for (Task task : taskMap.values()) {
            for (String dep : task.dependencies()) {
                if (!taskMap.containsKey(dep)) {
                    throw new IllegalArgumentException("任务 " + task.id() + " 依赖不存在的节点: " + dep);
                }
            }
        }
    }

    private void detectCycle() {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, List<String>> edges = new HashMap<>();
        for (Task task : taskMap.values()) {
            indegree.put(task.id(), 0);
            edges.put(task.id(), new ArrayList<>());
        }
        for (Task task : taskMap.values()) {
            for (String dep : task.dependencies()) {
                edges.get(dep).add(task.id());
                indegree.put(task.id(), indegree.get(task.id()) + 1);
            }
        }

        ArrayDeque<String> q = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                q.add(entry.getKey());
            }
        }

        int visited = 0;
        while (!q.isEmpty()) {
            String cur = q.removeFirst();
            visited++;
            for (String next : edges.get(cur)) {
                int val = indegree.get(next) - 1;
                indegree.put(next, val);
                if (val == 0) {
                    q.add(next);
                }
            }
        }
        if (visited != taskMap.size()) {
            throw new IllegalArgumentException("检测到循环依赖，无法执行 DAG 计划");
        }
    }

    public void markUnreachableAsSkipped() {
        Set<String> failed = new HashSet<>();
        for (Task task : taskMap.values()) {
            if (task.status() == TaskStatus.FAILED) {
                failed.add(task.id());
            }
        }
        if (failed.isEmpty()) {
            return;
        }
        boolean changed;
        do {
            changed = false;
            for (Task task : taskMap.values()) {
                if (task.status() != TaskStatus.PENDING) {
                    continue;
                }
                for (String dep : task.dependencies()) {
                    Task depTask = taskMap.get(dep);
                    if (depTask != null && (depTask.status() == TaskStatus.FAILED || depTask.status() == TaskStatus.SKIPPED)) {
                        task.setStatus(TaskStatus.SKIPPED);
                        task.setError("依赖任务失败或跳过: " + dep);
                        changed = true;
                        break;
                    }
                }
            }
        } while (changed);
    }
}
