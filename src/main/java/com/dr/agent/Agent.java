package com.dr.agent;

import com.dr.llm.DSV4Client;
import com.dr.memory.ContextCompressor;
import com.dr.memory.LongTermMemoryStore;
import com.dr.memory.MemoryEntry;
import com.dr.memory.MemoryRetriever;
import com.dr.memory.ShortTermMemory;
import com.dr.memory.TokenBudgetManager;
import com.dr.plan.ExecutionPlan;
import com.dr.plan.PlanExecutionReport;
import com.dr.plan.PlanExecutor;
import com.dr.plan.Planner;
import com.dr.team.AgentMessage;
import com.dr.team.MultiAgentCoordinator;
import com.dr.team.TeamExecutionResult;
import com.dr.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;

public class Agent {
    private static final Logger LOG = LoggerFactory.getLogger(Agent.class);
    private static final int MAX_HISTORY_MESSAGES = 80;

    private final DSV4Client llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanExecutor executor;
    private final MultiAgentCoordinator multiAgentCoordinator;
    private final ReActExecutor reActExecutor;
    private final List<Message> conversationHistory;
    private final LongTermMemoryStore longTermMemoryStore;
    private final MemoryRetriever memoryRetriever;
    private final ShortTermMemory shortTermMemory;
    private final ContextCompressor contextCompressor;
    private final TokenBudgetManager tokenBudgetManager;
    private PlanExecutionReport lastPlanReport;
    private List<AgentMessage> lastTeamMessages;
    private boolean teamModeEnabled;

    public Agent(String apiKey, String model, String baseUrl, ToolRegistry toolRegistry, Path workspaceRoot) {
        this.llmClient = new DSV4Client(apiKey, model, baseUrl);
        this.toolRegistry = toolRegistry;
        this.planner = new Planner(llmClient);
        this.executor = new PlanExecutor(llmClient, toolRegistry);
        this.multiAgentCoordinator = new MultiAgentCoordinator(llmClient, toolRegistry);
        this.reActExecutor = new ReActExecutor(llmClient, toolRegistry);
        this.conversationHistory = new ArrayList<>();
        this.longTermMemoryStore = new LongTermMemoryStore(workspaceRoot);
        this.memoryRetriever = new MemoryRetriever();
        this.shortTermMemory = new ShortTermMemory(8);
        this.contextCompressor = new ContextCompressor(llmClient);
        this.tokenBudgetManager = new TokenBudgetManager(6000, 1400, 22);
        this.conversationHistory.add(Message.system(SYSTEM_PROMPT));
        this.lastPlanReport = null;
        this.lastTeamMessages = List.of();
        this.teamModeEnabled = false;
    }

    private static final String SYSTEM_PROMPT = """
    你是一个面向工程实践的 CLI 编程助手。
    回答要求：
    1. 默认中文输出，先给结论再给步骤。
    2. 如果需要读取/写入文件或执行命令，优先使用工具调用。
    3. 工具返回后要继续推理，直到能给出最终可执行建议。
    4. 结果必须尽量可复制、可验证。
    5. 涉及代码定位、类关系、调用链时，优先调用 search_code 进行检索再回答。
    6. ReAct 与 Plan-and-Execute 模式都应主动利用 search_code 完成代码库理解。
    7. 涉及实时信息、外部网页或联网验证时，优先使用 web_search / web_fetch 工具。
    8. 是否联网由你自主判断，若本地上下文不足应主动触发联网工具。
    """;

    public String run(String userInput) {
        conversationHistory.add(Message.user(userInput));
        trimHistory();

        List<MemoryEntry> allMemories = longTermMemoryStore.load();
        List<MemoryEntry> retrieved = memoryRetriever.retrieveTopK(allMemories, userInput, 4);
        longTermMemoryStore.save(allMemories); // 持久化命中次数

        String compressedContext = "";
        if (conversationHistory.size() > 18) {
            int end = Math.max(2, conversationHistory.size() - 10);
            compressedContext = contextCompressor.compress(conversationHistory.subList(1, end));
        }
        List<Message> managedContext = tokenBudgetManager.buildManagedContext(
                conversationHistory,
                retrieved,
                shortTermMemory.snapshot(),
                compressedContext
        );

        try {
            PlanExecutionReport report;
            if (teamModeEnabled) {
                TeamExecutionResult team = multiAgentCoordinator.execute(userInput, new ArrayList<>(managedContext));
                report = team.report();
                lastTeamMessages = team.messages();
            } else {
                ExecutionPlan plan = planner.createPlan(userInput, managedContext);
                report = executor.execute(plan, new ArrayList<>(managedContext));
                lastTeamMessages = List.of();
            }
            this.lastPlanReport = report;
            String finalAnswer = report.finalAnswer();
            conversationHistory.add(Message.assistant(finalAnswer));
            shortTermMemory.addTurn(userInput, finalAnswer);
            longTermMemoryStore.appendMemory(userInput, finalAnswer);
            trimHistory();
            return finalAnswer;
        } catch (Exception e) {
            LOG.warn("Plan-and-Execute 执行失败，降级直接对话", e);
            String fallback = reActExecutor.execute(managedContext);
            conversationHistory.add(Message.assistant(fallback));
            shortTermMemory.addTurn(userInput, fallback);
            longTermMemoryStore.appendMemory(userInput, fallback);
            trimHistory();
            return fallback;
        }
    }

    public void clearHistory() {
        conversationHistory.clear();
        conversationHistory.add(Message.system(SYSTEM_PROMPT));
        shortTermMemory.clear();
        lastPlanReport = null;
        lastTeamMessages = List.of();
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
        // 从恢复会话构建短期记忆窗口。
        for (int i = Math.max(1, conversationHistory.size() - 10); i < conversationHistory.size() - 1; i += 2) {
            Message user = conversationHistory.get(i);
            Message assistant = conversationHistory.get(i + 1);
            if ("user".equals(user.getRole()) && "assistant".equals(assistant.getRole())) {
                shortTermMemory.addTurn(user.getContent(), assistant.getContent());
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

    public void setTeamModeEnabled(boolean enabled) {
        this.teamModeEnabled = enabled;
    }

    public boolean isTeamModeEnabled() {
        return teamModeEnabled;
    }

    public String latestTeamSummary() {
        if (lastTeamMessages == null || lastTeamMessages.isEmpty()) {
            return "暂无多 Agent 协作记录。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Team 消息流:\n");
        for (AgentMessage msg : lastTeamMessages) {
            sb.append("- ")
                    .append(msg.timestamp())
                    .append(" [").append(msg.type()).append("] ")
                    .append(msg.fromRole()).append(" -> ").append(msg.toRole())
                    .append(" task=").append(msg.taskId() == null ? "-" : msg.taskId())
                    .append(" | ").append(msg.content())
                    .append("\n");
        }
        return sb.toString();
    }

    public ToolRegistry getToolRegistry() {
        return toolRegistry;
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
