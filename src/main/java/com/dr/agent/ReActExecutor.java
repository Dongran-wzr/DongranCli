package com.dr.agent;

import com.dr.llm.DSV4Client;
import com.dr.tool.ToolRegistry;
import com.dr.tool.exec.ParallelToolExecutionEngine;
import com.dr.tool.exec.ToolExecutionResult;
import com.dr.tool.exec.ToolInvocation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
        List<Message> working = new ArrayList<>(managedContext);
        int iteration = 0;
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            ChatResponse response = llmClient.chat(working, toolRegistry.getToolDefinitions());
            if (!response.hasToolCalls()) {
                return response.content();
            }
            working.add(Message.assistant(response.content(), response.toolCalls()));
            List<ToolInvocation> batch = new ArrayList<>();
            for (ToolCall call : response.toolCalls()) {
                batch.add(new ToolInvocation(call.getId(), call.getName(), call.getArgumentsJson()));
            }
            List<ToolExecutionResult> results = toolEngine.executeBatch(batch, TOOL_BATCH_TIMEOUT);
            for (ToolExecutionResult result : results) {
                working.add(Message.tool(result.toolCallId(), result.toToolMessageContent()));
            }
        }
        return "ReAct 达到最大迭代次数";
    }
}
