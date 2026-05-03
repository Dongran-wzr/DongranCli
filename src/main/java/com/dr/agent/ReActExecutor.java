package com.dr.agent;

import com.dr.llm.DSV4Client;
import com.dr.tool.ToolRegistry;
import com.dr.tool.exec.ParallelToolExecutionEngine;
import com.dr.tool.exec.ToolExecutionResult;
import com.dr.tool.exec.ToolInvocation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ReActExecutor {
    private static final int MAX_ITERATIONS = 8;
    private static final Duration TOOL_BATCH_TIMEOUT = Duration.ofSeconds(30);

    private final DSV4Client llmClient;
    private final ToolRegistry toolRegistry;
    private final ParallelToolExecutionEngine toolEngine;

    public ReActExecutor(DSV4Client llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.toolEngine = new ParallelToolExecutionEngine(toolRegistry);
    }

    public String execute(List<Message> managedContext) {
        return execute(managedContext, null);
    }

    public String execute(List<Message> managedContext, Consumer<String> progressCallback) {
        Consumer<String> progress = progressCallback == null ? s -> { } : progressCallback;
        List<Message> working = new ArrayList<>(managedContext);
        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            progress.accept("🧠 ReAct: 第 " + iteration + " 轮推理");
            ChatResponse response = llmClient.chat(working, toolRegistry.getToolDefinitions());
            if (!response.hasToolCalls()) {
                return response.content();
            }
            working.add(Message.assistant(response.content(), response.toolCalls()));
            List<ToolInvocation> batch = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                batch.add(new ToolInvocation(call.getId(), call.getName(), call.getArgumentsJson()));
            }
            progress.accept("🛠️ 工具调用: " + response.toolCalls().size() + " 个");
            List<ToolExecutionResult> results = toolEngine.executeBatch(batch, TOOL_BATCH_TIMEOUT);
            for (ToolExecutionResult result : results) {
                working.add(Message.tool(result.toolCallId(), result.toToolMessageContent()));
            }
        }
        progress.accept("⚠️ ReAct: 工具调用达到上限，尝试强制收敛回答");
        return forceFinalizeAfterToolLimit(working);
    }

    private String forceFinalizeAfterToolLimit(List<Message> working) {
        List<Message> forcedMessages = new ArrayList<>(working);
        forcedMessages.add(Message.user("""
                你已达到工具调用上限，请不要再调用工具。
                基于已有上下文直接输出最终答案；
                若信息不足，请明确缺失信息并给出下一步建议。
                """));
        ChatResponse forced = llmClient.chat(forcedMessages, List.of());
        if (forced == null || forced.content() == null || forced.content().isBlank()) {
            return "工具调用已达上限，未能稳定收敛。请缩小问题范围或分步骤执行。";
        }
        return forced.content();
    }
}
