package com.dr.team;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.llm.DSV4Client;
import com.dr.plan.ExecutionPlan;
import com.dr.plan.PlanExecutionReport;
import com.dr.plan.Planner;
import com.dr.plan.Task;
import com.dr.plan.TaskStatus;
import com.dr.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class MultiAgentCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(MultiAgentCoordinator.class);
    private static final int MAX_RETRIES = 2;
    private static final int MAX_WORKERS = 4;

    private final DSV4Client llmClient;
    private final Planner planner;
    private final BlockingQueue<WorkerAgent> availableWorkers;

    public MultiAgentCoordinator(DSV4Client llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.planner = new Planner(llmClient);
        this.availableWorkers = new LinkedBlockingQueue<>();
        int workerCount = Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), MAX_WORKERS));
        for (int i = 0; i < workerCount; i++) {
            availableWorkers.offer(new WorkerAgent(llmClient, toolRegistry));
        }
    }

    public TeamExecutionResult execute(String objective, List<Message> baseContext) {
        Instant startedAt = Instant.now();
        ExecutionPlan plan = planner.createPlan(objective, baseContext);
        List<AgentMessage> bus = new CopyOnWriteArrayList<>();
        bus.add(AgentMessage.of(
                AgentMessageType.PLAN_CREATED,
                AgentRole.PLANNER,
                AgentRole.MASTER,
                null,
                "生成计划任务数: " + plan.tasks().size()
        ));

        BlockingQueue<WorkItem> queue = new LinkedBlockingQueue<>();
        Set<String> queued = new HashSet<>();
        Object lock = new Object();
        enqueueReadyTasks(plan, queue, queued, lock);

        int workerThreads = Math.max(1, Math.min(availableWorkers.size(), MAX_WORKERS));
        ExecutorService pool = Executors.newFixedThreadPool(workerThreads);
        for (int i = 0; i < workerThreads; i++) {
            pool.submit(() -> workerLoop(plan, baseContext, queue, queued, bus, lock));
        }

        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String finalAnswer = synthesizeFinalAnswer(plan, baseContext);
        bus.add(AgentMessage.of(
                AgentMessageType.WORKFLOW_FINISHED,
                AgentRole.MASTER,
                AgentRole.MASTER,
                null,
                "多 Agent 工作流完成"
        ));

        PlanExecutionReport report = buildReport(plan, startedAt, Instant.now(), finalAnswer);
        return new TeamExecutionResult(report, bus);
    }

    private void workerLoop(
            ExecutionPlan plan,
            List<Message> baseContext,
            BlockingQueue<WorkItem> queue,
            Set<String> queued,
            List<AgentMessage> bus,
            Object lock
    ) {
        while (true) {
            if (isDone(plan, queue, lock)) {
                return;
            }

            WorkItem item;
            try {
                item = queue.poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (item == null) {
                continue;
            }

            Task task;
            synchronized (lock) {
                queued.remove(item.taskId());
                task = plan.getTask(item.taskId());
                if (task == null || task.status() == TaskStatus.SUCCEEDED || task.status() == TaskStatus.SKIPPED) {
                    continue;
                }
                task.setStatus(TaskStatus.RUNNING);
            }

            bus.add(AgentMessage.of(
                    AgentMessageType.TASK_DISPATCHED,
                    AgentRole.MASTER,
                    AgentRole.WORKER,
                    task.id(),
                    "分配任务 attempt=" + item.attempt()
            ));

            long started = System.currentTimeMillis();
            WorkerResult workerResult;
            WorkerAgent worker = null;
            try {
                worker = availableWorkers.take();
                workerResult = worker.executeTask(plan, task, baseContext, item.attempt(), item.lastReviewFeedback());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                workerResult = new WorkerResult(false, null, "worker 池分配中断", 0);
            } finally {
                if (worker != null) {
                    availableWorkers.offer(worker);
                }
            }
            ReviewerAgent stepReviewer = new ReviewerAgent(llmClient); // 每步独立创建，避免对话历史竞争
            ReviewResult reviewResult = workerResult.success()
                    ? stepReviewer.review(task, workerResult.output())
                    : new ReviewResult(false, workerResult.error());

            synchronized (lock) {
                task.setAttempts(item.attempt());
                task.setDurationMs(System.currentTimeMillis() - started);

                bus.add(AgentMessage.of(
                        AgentMessageType.TASK_RESULT,
                        AgentRole.WORKER,
                        AgentRole.REVIEWER,
                        task.id(),
                        workerResult.success() ? trim(workerResult.output()) : "执行失败: " + workerResult.error()
                ));
                bus.add(AgentMessage.of(
                        AgentMessageType.REVIEW_FEEDBACK,
                        AgentRole.REVIEWER,
                        AgentRole.MASTER,
                        task.id(),
                        reviewResult.feedback()
                ));

                if (workerResult.success() && reviewResult.approved()) {
                    task.setStatus(TaskStatus.SUCCEEDED);
                    task.setOutput(workerResult.output());
                    task.setError(null);
                    enqueueReadyTasks(plan, queue, queued, lock);
                } else {
                    if (item.attempt() <= MAX_RETRIES) {
                        task.setStatus(TaskStatus.PENDING);
                        queue.offer(new WorkItem(task.id(), item.attempt() + 1, reviewResult.feedback()));
                        queued.add(task.id());
                        bus.add(AgentMessage.of(
                                AgentMessageType.RETRY_SCHEDULED,
                                AgentRole.MASTER,
                                AgentRole.WORKER,
                                task.id(),
                                "任务重试，原因: " + reviewResult.feedback()
                        ));
                    } else {
                        task.setStatus(TaskStatus.FAILED);
                        task.setError(reviewResult.feedback());
                        plan.markUnreachableAsSkipped();
                        enqueueReadyTasks(plan, queue, queued, lock);
                    }
                }
            }
        }
    }

    private void enqueueReadyTasks(ExecutionPlan plan, BlockingQueue<WorkItem> queue, Set<String> queued, Object lock) {
        synchronized (lock) {
            for (Task ready : plan.getReadyTasks()) {
                if (!queued.contains(ready.id())) {
                    queue.offer(new WorkItem(ready.id(), 1, ""));
                    queued.add(ready.id());
                }
            }
        }
    }

    private boolean isDone(ExecutionPlan plan, BlockingQueue<WorkItem> queue, Object lock) {
        synchronized (lock) {
            if (!queue.isEmpty()) {
                return false;
            }
            for (Task task : plan.tasks()) {
                if (task.status() == TaskStatus.PENDING || task.status() == TaskStatus.RUNNING) {
                    return false;
                }
            }
            return true;
        }
    }

    private String synthesizeFinalAnswer(ExecutionPlan plan, List<Message> baseContext) {
        StringBuilder summary = new StringBuilder();
        summary.append("目标: ").append(plan.objective()).append("\n");
        summary.append("多 Agent 任务结果:\n");
        for (Task task : plan.tasksSorted()) {
            summary.append("- ").append(task.id()).append(" [").append(task.status()).append("] ").append(task.description()).append("\n");
            if (task.output() != null && !task.output().isBlank()) {
                summary.append("  输出: ").append(trim(task.output())).append("\n");
            }
            if (task.error() != null && !task.error().isBlank()) {
                summary.append("  错误: ").append(task.error()).append("\n");
            }
        }
        List<Message> messages = new ArrayList<>(baseContext);
        messages.add(Message.user("""
                请基于以下多 Agent 执行摘要给出最终答复：
                1. 先结论，再步骤。
                2. 明确指出失败任务影响和后续建议。
                摘要:
                """ + summary));
        ChatResponse response = llmClient.chat(messages, List.of());
        return response.content();
    }

    private PlanExecutionReport buildReport(ExecutionPlan plan, Instant startedAt, Instant finishedAt, String finalAnswer) {
        List<PlanExecutionReport.TaskSnapshot> snapshots = new ArrayList<>();
        for (Task task : plan.tasksSorted()) {
            snapshots.add(new PlanExecutionReport.TaskSnapshot(
                    task.id(),
                    task.description(),
                    task.dependencies(),
                    task.status(),
                    task.attempts(),
                    task.durationMs(),
                    task.output(),
                    task.error()
            ));
        }
        return new PlanExecutionReport(plan.objective(), startedAt, finishedAt, snapshots, finalAnswer);
    }

    private String trim(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 260 ? value : value.substring(0, 260) + "...";
    }

    private record WorkItem(String taskId, int attempt, String lastReviewFeedback) {
    }
}
