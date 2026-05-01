package com.dr.plan;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.llm.DSV4Client;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class Planner {
    private static final Logger LOG = LoggerFactory.getLogger(Planner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DSV4Client llmClient;

    public Planner(DSV4Client llmClient) {
        this.llmClient = llmClient;
    }

    public ExecutionPlan createPlan(String objective, List<Message> history) {
        try {
            List<Message> plannerMessages = new ArrayList<>();
            plannerMessages.add(Message.system("""
                    你是 Planner，只负责把目标拆分为可执行 DAG 任务。
                    输出必须是 JSON，不要输出代码块标记。
                    JSON 格式:
                    {
                      "tasks": [
                        {"id":"T1","description":"...","deps":[]},
                        {"id":"T2","description":"...","deps":["T1"]}
                      ]
                    }
                    约束:
                    1. id 必须唯一且简短。
                    2. deps 只能引用已存在任务。
                    3. 任务数量控制在 2-6 个。
                    4. 如果目标非常简单，也至少给 1 个任务。
                    """));
            plannerMessages.add(Message.user("目标: " + objective));

            // 给 Planner 少量上下文，避免过长输入。
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                Message m = history.get(i);
                if ("user".equals(m.getRole()) || "assistant".equals(m.getRole())) {
                    plannerMessages.add(Message.user("上下文(" + m.getRole() + "): " + safe(m.getContent())));
                }
            }

            ChatResponse response = llmClient.chat(plannerMessages, List.of());
            return parsePlan(objective, response.content());
        } catch (Exception e) {
            LOG.warn("Planner 生成失败，降级单任务计划", e);
            return fallbackPlan(objective);
        }
    }

    private ExecutionPlan parsePlan(String objective, String rawContent) throws Exception {
        String json = extractJson(rawContent);
        JsonNode root = MAPPER.readTree(json);
        JsonNode tasksNode = root.path("tasks");
        if (!tasksNode.isArray() || tasksNode.isEmpty()) {
            return fallbackPlan(objective);
        }

        List<Task> tasks = new ArrayList<>();
        for (JsonNode t : tasksNode) {
            String id = safeId(t.path("id").asText(""));
            String description = t.path("description").asText("");
            List<String> deps = new ArrayList<>();
            JsonNode depsNode = t.path("deps");
            if (depsNode.isArray()) {
                for (JsonNode dep : depsNode) {
                    String depId = safeId(dep.asText(""));
                    if (!depId.isBlank()) {
                        deps.add(depId);
                    }
                }
            }
            if (id.isBlank()) {
                id = "T" + (tasks.size() + 1);
            }
            tasks.add(new Task(id, description, deps));
        }

        return new ExecutionPlan(objective, tasks);
    }

    private ExecutionPlan fallbackPlan(String objective) {
        return new ExecutionPlan(objective, List.of(new Task("T1", objective, List.of())));
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{\"tasks\":[]}";
        }
        String trimmed = raw.trim();
        int blockStart = trimmed.indexOf("```");
        if (blockStart >= 0) {
            int firstBrace = trimmed.indexOf('{', blockStart);
            int lastBrace = trimmed.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                return trimmed.substring(firstBrace, lastBrace + 1);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    private String safeId(String id) {
        return id == null ? "" : id.trim().replaceAll("[^a-zA-Z0-9_-]", "");
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
