package com.dr.agent;

import com.dr.llm.DSV4Client;
import com.dr.plan.ExecutionPlan;
import com.dr.plan.PlanExecutionReport;
import com.dr.plan.PlanExecutor;
import com.dr.plan.Planner;
import com.dr.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Agent {
    private static final Logger LOG = LoggerFactory.getLogger(Agent.class);
    private static final int MAX_HISTORY_MESSAGES = 80;

    private final DSV4Client llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanExecutor executor;
    private final List<Message> conversationHistory;
    private PlanExecutionReport lastPlanReport;

    public Agent(String apiKey, String model, String baseUrl, ToolRegistry toolRegistry) {
        this.llmClient = new DSV4Client(apiKey, model, baseUrl);
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
        this.executor = new PlanExecutor(llmClient, toolRegistry);
        this.conversationHistory = new ArrayList<>();
        this.conversationHistory.add(Message.system(SYSTEM_PROMPT));
        this.lastPlanReport = null;
    }

    private static final String SYSTEM_PROMPT = """
    你是一个面向工程实践的 CLI 编程助手。
    回答要求：
    1. 默认中文输出，先给结论再给步骤。
    2. 如果需要读取/写入文件或执行命令，优先使用工具调用。
    3. 工具返回后要继续推理，直到能给出最终可执行建议。
    4. 结果必须尽量可复制、可验证。
    """;

    public String run(String userInput) {
        conversationHistory.add(Message.user(userInput));
        trimHistory();

        try {
            ExecutionPlan plan = planner.createPlan(userInput, conversationHistory);
            conversationHistory.add(Message.assistant("[Planner] 已生成 DAG 计划，任务数: " + plan.tasks().size()));
            PlanExecutionReport report = executor.execute(plan, conversationHistory);
            this.lastPlanReport = report;
            String finalAnswer = report.finalAnswer();
            conversationHistory.add(Message.assistant(finalAnswer));
            trimHistory();
            return finalAnswer;
        } catch (Exception e) {
            LOG.warn("Plan-and-Execute 执行失败，降级直接对话", e);
            ChatResponse fallback = llmClient.chat(conversationHistory, toolRegistry.getToolDefinitions());
            conversationHistory.add(Message.assistant(fallback.content()));
            trimHistory();
            return fallback.content();
        }
    }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(Message.system(SYSTEM_PROMPT));
        lastPlanReport = null;
    }

    public List<Message> snapshotHistory() {
        return List.copyOf(conversationHistory);
    }

    public void restoreHistory(List<Message> messages) {
        clearHistory();
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (Message m : messages) {
            if (m != null && m.getRole() != null && !m.getRole().isBlank()) {
                conversationHistory.add(m);
            }
        }
        trimHistory();
    }

    public String latestPlanSummary() {
        if (lastPlanReport == null) {
            return "暂无计划执行记录。请先发起一次任务。";
        }
        return lastPlanReport.toHumanReadable();
    }

    private void trimHistory() {
        if (conversationHistory.size() <= MAX_HISTORY_MESSAGES) {
            return;
        }
        Message system = conversationHistory.get(0);
        List<Message> tail = conversationHistory.subList(
                conversationHistory.size() - (MAX_HISTORY_MESSAGES - 1),
                conversationHistory.size()
        );
        List<Message> compacted = new ArrayList<>();
        compacted.add(system);
        compacted.addAll(tail);
        conversationHistory.clear();
        conversationHistory.addAll(compacted);
    }
}
