package com.dr.team;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.agent.ToolCall;
import com.dr.llm.DSV4Client;
import com.dr.plan.ExecutionPlan;
import com.dr.plan.Task;
import com.dr.tool.ToolRegistry;
import com.dr.tool.exec.ParallelToolExecutionEngine;
import com.dr.tool.exec.ToolExecutionResult;
import com.dr.tool.exec.ToolInvocation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class WorkerAgent {
    private static final int MAX_TOOL_ITERATIONS = 6;
    private static final Duration TOOL_BATCH_TIMEOUT = Duration.ofSeconds(30);

    private final DSV4Client llmClient;
    private final ToolRegistry toolRegistry;
    private final ParallelToolExecutionEngine toolEngine;

    public WorkerAgent(DSV4Client llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolEngine = new ParallelToolExecutionEngine(toolRegistry);
    }

    public WorkerResult executeTask(ExecutionPlan plan, Task task, List<Message> sharedContext, int attempt, String reviewFeedback) {
        List<Message> working = new ArrayList<>(sharedContext);
        working.add(Message.user(buildTaskPrompt(plan, task, attempt, reviewFeedback)));

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
                return new WorkerResult(false, null, "worker 输出为空", iteration);
            }
            return new WorkerResult(true, output, null, iteration);
        }
        return new WorkerResult(false, null, "worker 工具调用超过迭代上限", MAX_TOOL_ITERATIONS);
    }

    private String buildTaskPrompt(ExecutionPlan plan, Task task, int attempt, String reviewFeedback) {
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
                deps.append(" / ").append(truncate(depTask.output(), 200));
            }
            deps.append("\n");
        }
        String reviewerHint = reviewFeedback == null || reviewFeedback.isBlank()
                ? "无"
                : reviewFeedback;

        return """
                你是 Worker 子 Agent，正在执行 DAG 节点任务。
                要求：
                1. 仅聚焦当前任务。
                2. 需要时调用工具。
                3. 产出可被 Reviewer 审查的明确结果。
                目标: %s
                当前任务: %s - %s
                尝试次数: %d
                上次审查反馈: %s
                依赖结果:
                %s
                """.formatted(
                plan.objective(),
                task.id(),
                task.description(),
                attempt,
                reviewerHint,
                deps.isEmpty() ? "(无依赖)" : deps.toString()
        );
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }
}
