package com.dr.llm;

import com.dr.agent.ChatResponse;
import com.dr.agent.Message;
import com.dr.agent.ToolCall;
import com.dr.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class DSV4Client {
    private static final Logger LOG = LoggerFactory.getLogger(DSV4Client.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String apiUrl;
    private final String model;
    private final OkHttpClient httpClient;

    public DSV4Client(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        String normalizedBase = (baseUrl == null || baseUrl.isBlank())
                ? DEFAULT_API_URL
                : baseUrl.trim();
        this.apiUrl = normalizedBase;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    public ChatResponse chat(List<Message> history, List<ToolRegistry.ToolDefinition> tools) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.put("temperature", 0.2);
        payload.set("messages", buildMessages(history));

        if (tools != null && !tools.isEmpty()) {
            payload.set("tools", buildTools(tools));
            payload.put("tool_choice", "auto");
        }

        Request request = new Request.Builder()
                .url(apiUrl)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() == null ? "" : response.body().string();
                return new ChatResponse("LLM 请求失败: HTTP " + response.code() + " " + body, List.of());
            }

            String body = response.body() == null ? "{}" : response.body().string();
            JsonNode root = mapper.readTree(body);
            JsonNode choice = root.path("choices").path(0);
            JsonNode message = choice.path("message");
            String content = message.path("content").asText("");

            List<ToolCall> toolCalls = parseToolCalls(message.path("tool_calls"));
            return new ChatResponse(content, toolCalls);
        } catch (IOException e) {
            LOG.warn("LLM 调用异常", e);
            return new ChatResponse("LLM 请求异常: " + e.getMessage(), List.of());
        }
    }

    private ArrayNode buildMessages(List<Message> history) {
        ArrayNode messages = mapper.createArrayNode();
        for (Message msg : history) {
            ObjectNode item = mapper.createObjectNode();
            item.put("role", msg.getRole());
            if (msg.getContent() != null) {
                item.put("content", msg.getContent());
            }
            if (msg.getToolCallId() != null) {
                item.put("tool_call_id", msg.getToolCallId());
            }
            if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
                ArrayNode toolCalls = item.putArray("tool_calls");
                for (ToolCall call : msg.getToolCalls()) {
                    ObjectNode tc = toolCalls.addObject();
                    tc.put("id", call.getId());
                    tc.put("type", "function");
                    ObjectNode fn = tc.putObject("function");
                    fn.put("name", call.getName());
                    fn.put("arguments", call.getArgumentsJson() == null ? "{}" : call.getArgumentsJson());
                }
            }
            messages.add(item);
        }
        return messages;
    }

    private ArrayNode buildTools(List<ToolRegistry.ToolDefinition> tools) {
        ArrayNode toolList = mapper.createArrayNode();
        for (ToolRegistry.ToolDefinition tool : tools) {
            ObjectNode item = toolList.addObject();
            item.put("type", "function");
            ObjectNode fn = item.putObject("function");
            fn.put("name", tool.name());
            fn.put("description", tool.description());
            fn.set("parameters", tool.parameters());
        }
        return toolList;
    }

    private List<ToolCall> parseToolCalls(JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode n : toolCallsNode) {
            String id = n.path("id").asText("");
            JsonNode fn = n.path("function");
            String name = fn.path("name").asText("");
            String arguments = fn.path("arguments").asText("{}");
            if (!name.isBlank()) {
                calls.add(new ToolCall(id, name, arguments));
            }
        }
        return calls;
    }

}
