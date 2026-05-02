package com.dr.team;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.llm.DSV4Client;
import com.dr.plan.Task;

import java.util.ArrayList;
import java.util.List;

public class ReviewerAgent {
    private final DSV4Client llmClient;

    public ReviewerAgent(DSV4Client llmClient) {
        this.llmClient = llmClient;
    }

    public ReviewResult review(Task task, String workerOutput) {
        try {
            List<Message> prompt = new ArrayList<>();
            prompt.add(Message.system("""
                    你是 Reviewer，请审查 Worker 任务结果是否满足任务目标。
                    输出格式必须是两行：
                    APPROVED: yes/no
                    FEEDBACK: <反馈>
                    """));
            prompt.add(Message.user("""
                    任务: %s
                    Worker 输出:
                    %s
                    """.formatted(task.description(), workerOutput == null ? "" : workerOutput)));
            ChatResponse response = llmClient.chat(prompt, List.of());
            return parse(response.content());
        } catch (Exception e) {
            return new ReviewResult(false, "Reviewer 审查失败: " + e.getMessage());
        }
    }

    private ReviewResult parse(String content) {
        if (content == null) {
            return new ReviewResult(false, "Reviewer 返回为空");
        }
        String lower = content.toLowerCase();
        boolean approved = lower.contains("approved: yes");
        String feedback = content;
        int idx = lower.indexOf("feedback:");
        if (idx >= 0) {
            feedback = content.substring(idx + "feedback:".length()).trim();
        }
        if (feedback.isBlank()) {
            feedback = approved ? "通过" : "未通过";
        }
        return new ReviewResult(approved, feedback);
    }
}
