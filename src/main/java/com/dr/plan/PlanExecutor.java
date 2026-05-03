package com.dr.plan;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.agent.ToolCall;
import com.dr.llm.DSV4Client;
import com.dr.tool.ToolRegistry;
import com.dr.tool.exec.ParallelToolExecutionEngine;
import com.dr.tool.exec.ToolExecutionResult;
import com.dr.tool.exec.ToolInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public class PlanExecutor {
    private static final Logger LOG = LoggerFactory.getLogger(PlanExecutor.class);
    private static final int MAX_TOOL_ITERATIONS = 6;
    private static final int MAX_TASK_RETRIES = 2;
    private static final Duration TOOL_BATCH_TIMEOUT = Duration.ofSeconds(30);

    private final DSV4Client llmClient;
    private final ToolRegistry toolRegistry;
    private final ParallelToolExecutionEngine toolEngine;

    public PlanExecutor(DSV4Client llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolEngine = new ParallelToolExecutionEngine(toolRegistry);
    }

    public PlanExecutionReport execute(ExecutionPlan plan, List<Message> sharedHistory) {
        return execute(plan, sharedHistory, null, null);
    }

    public PlanExecutionReport execute(
            ExecutionPlan plan,
            List<Message> sharedHistory,
            Consumer<String> progressCallback,
            Consumer<String> answerChunkCallback
    ) {
        Consumer<String> progress = progressCallback == null ? s -> { } : progressCallback;
        Instant startedAt = Instant.now();

        while (!plan.allFinished()) {
            List<Task> ready = plan.getReadyTasks();
            if (ready.isEmpty()) {
                plan.markUnreachableAsSkipped();
                break;
            }
            progress.accept("🧩 任务调度: 本轮可执行任务 " + ready.size() + " 个");

            List<TaskRunResult> roundResults = executeReadyTasksInParallel(plan, ready, sharedHistory);
            writeRoundOutputOrdered(roundResults, sharedHistory);
            plan.markUnreachableAsSkipped();
        }

        progress.accept("🧠 总结阶段: 正在生成最终回答");
        String finalAnswer = synthesizeFinalAnswer(plan, sharedHistory, answerChunkCallback);
        return buildReport(plan, startedAt, Instant.now(), finalAnswer);
    }

    private List<TaskRunResult> executeReadyTasksInParallel(ExecutionPlan plan, List<Task> ready, List<Message> sharedHistory) {
        int parallelism = Math.max(1, Math.min(ready.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        List<Future<TaskRunResult>> futures = new ArrayList<>();
        try {
            for (Task task : ready) {
                futures.add(pool.submit(new Callable<>() {
                    @Override
                    public TaskRunResult call() {
                        return executeTaskWithRetry(plan, task, sharedHistory);
                    }
                }));
            }

            List<TaskRunResult> results = new ArrayList<>();
            for (Future<TaskRunResult> f : futures) {
                try {
                    results.add(f.get());
                } catch (Exception e) {
                    LOG.warn("并行任务执行失败", e);
                    results.add(new TaskRunResult("UNKNOWN", "[UNKNOWN 失败] 并行执行异常: " + e.getMessage()));
                }
            }
            return results;
        } finally {
            pool.shutdown();
        }
    }

    private void writeRoundOutputOrdered(List<TaskRunResult> roundResults, List<Message> sharedHistory) {
        roundResults.sort((a, b) -> a.taskId().compareToIgnoreCase(b.taskId()));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        for (TaskRunResult result : roundResults) {
            byte[] line = (result.message() + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            buffer.write(line, 0, line.length);
        }
        String merged = new String(buffer.toByteArray(), StandardCharsets.UTF_8).trim();
        if (!merged.isBlank()) {
            for (String line : merged.split("\\R")) {
                if (!line.isBlank()) {
                    sharedHistory.add(Message.assistant(line));
                }
            }
        }
    }

    private TaskRunResult executeTaskWithRetry(ExecutionPlan plan, Task task, List<Message> sharedHistory) {
        task.setStatus(TaskStatus.RUNNING);
        LOG.info("执行任务: {} - {}", task.id(), task.description());
        long started = System.currentTimeMillis();

        String lastError = null;
        for (int attempt = 1; attempt <= (MAX_TASK_RETRIES + 1); attempt++) {
            TaskAttemptResult attemptResult = executeTaskOnce(plan, task, sharedHistory, attempt, lastError);
            task.setAttempts(attempt);
            if (attemptResult.success()) {
                task.setOutput(attemptResult.output());
                task.setError(null);
                task.setStatus(TaskStatus.SUCCEEDED);
                task.setDurationMs(System.currentTimeMillis() - started);
                return new TaskRunResult(task.id(), "[" + task.id() + " 完成] " + attemptResult.output());
            }
            lastError = attemptResult.error();
        }

        task.setStatus(TaskStatus.FAILED);
        task.setError(lastError == null ? "未知错误" : lastError);
        task.setDurationMs(System.currentTimeMillis() - started);
        return new TaskRunResult(task.id(), "[" + task.id() + " 失败] " + task.error());
    }

    private TaskAttemptResult executeTaskOnce(ExecutionPlan plan, Task task, List<Message> sharedHistory, int attempt, String lastError) {
        List<Message> working = new ArrayList<>(sharedHistory); // 回滚策略：失败时不写回 sharedHistory
        working.add(Message.user(buildTaskPrompt(plan, task)));
        if (attempt > 1 && lastError != null) {
            working.add(Message.user("上次尝试失败，错误为: " + lastError + "。请改用不同策略重试。"));
        }

        int iteration = 0;
        while (iteration < MAX_TOOL_ITERATIONS) {
            iteration++;
            ChatResponse response = llmClient.chat(working, toolRegistry.getToolDefinitions());

            if (response.hasToolCalls()) {
                working.add(Message.assistant(response.content(), response.toolCalls()));
                List<ToolInvocation> batch = new ArrayList<>();
                for (ToolCall call : response.toolCalls()) {
                    batch.add(new ToolInvocation(call.getId(), call.getName(), call.getArgumentsJson()));
                }
                List<ToolExecutionResult> results = toolEngine.executeBatch(batch, TOOL_BATCH_TIMEOUT);
                for (ToolExecutionResult r : results) {
                    working.add(Message.tool(r.toolCallId(), r.toToolMessageContent()));
                }
                continue;
            }
            String output = response.content();
            if (output == null || output.isBlank()) {
                return new TaskAttemptResult(false, null, "模型返回为空");
            }
            return new TaskAttemptResult(true, output, null);
        }
        String forced = forceFinalizeAfterToolLimit(working, task.id(), task.description());
        if (forced == null || forced.isBlank()) {
            return new TaskAttemptResult(false, null, "工具调用迭代次数超过限制");
        }
        return new TaskAttemptResult(true, forced, null);
    }

    private String forceFinalizeAfterToolLimit(List<Message> working, String taskId, String taskDescription) {
        List<Message> forcedMessages = new ArrayList<>(working);
        forcedMessages.add(Message.user("""
                你已达到工具调用上限，请立即停止调用任何工具。
                仅基于当前上下文直接给出任务结果：
                - taskId: %s
                - task: %s
                若信息不足，请明确缺失项并给出下一步建议。
                """.formatted(taskId, taskDescription)));
        ChatResponse forced = llmClient.chat(forcedMessages, List.of());
        if (forced == null) {
            return "";
        }
        String content = forced.content();
        if (content == null || content.isBlank()) {
            return "工具调用已达上限，且模型未返回可用结论。请缩小问题范围或分步骤提问。";
        }
        return content;
    }

    private String synthesizeFinalAnswer(
            ExecutionPlan plan,
            List<Message> sharedHistory,
            Consumer<String> answerChunkCallback
    ) {
        StringBuilder summary = new StringBuilder();
        summary.append("目标: ").append(plan.objective()).append("\n");
        summary.append("任务执行结果:\n");
        for (Task task : plan.tasksSorted()) {
            summary.append("- ").append(task.id())
                    .append(" [").append(task.status()).append("] ")
                    .append(task.description()).append("\n");
            if (task.output() != null && !task.output().isBlank()) {
                summary.append("  输出: ").append(truncate(task.output(), 300)).append("\n");
            }
            if (task.error() != null && !task.error().isBlank()) {
                summary.append("  错误: ").append(task.error()).append("\n");
            }
        }

        List<Message> messages = new ArrayList<>(sharedHistory);
        messages.add(Message.user("""
                请根据以下计划执行摘要，给出最终答复：
                要求：
                1. 先给结果，再给关键步骤。
                2. 若存在失败/跳过任务，明确说明影响范围与下一步建议。
                摘要:
                """ + summary));

        if (answerChunkCallback != null) {
            return llmClient.chatStreamText(messages, answerChunkCallback);
        }
        ChatResponse finalResponse = llmClient.chat(messages, List.of());
        return finalResponse.content();
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

    private String buildTaskPrompt(ExecutionPlan plan, Task task) {
        StringBuilder deps = new StringBuilder();
        for (String depId : task.dependencies()) {
            Task depTask = plan.getTask(depId);
            deps.append("- ").append(depId).append(": ");
            if (depTask == null) {
                deps.append("缺失\n");
                continue;
            }
            deps.append(depTask.status());
            if (depTask.output() != null && !depTask.output().isBlank()) {
                deps.append(" / ").append(truncate(depTask.output(), 220));
            }
            deps.append("\n");
        }

        return """
                你正在执行 DAG 任务节点，请专注当前节点并可按需调用工具。
                输出要求：完成当前任务并给出简洁结论。
                目标: %s
                当前任务: %s - %s
                依赖结果:
                %s
                """.formatted(
                plan.objective(),
                task.id(),
                task.description(),
                deps.isEmpty() ? "(无依赖)" : deps.toString()
        );
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }

    private record TaskRunResult(String taskId, String message) {
    }

    private record TaskAttemptResult(boolean success, String output, String error) {
    }
}
