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
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class DSV4Client {
    private static final Logger LOG = LoggerFactory.getLogger(DSV4Client.class);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String DEFAULT_OPENAI_CHAT_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final String DEFAULT_EMBEDDING_MODEL = "nomic-embed-text";
    private static final int MAX_EMBED_TEXT_CHARS = 8000;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Provider provider;

    private final String apiKey;
    private final String chatUrl;
    private final String embeddingsUrl;
    private final String model;
    private final String embeddingModel;
    private final OkHttpClient httpClient;

    public DSV4Client(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = (model == null || model.isBlank()) ? DEFAULT_MODEL : model.trim();
        this.provider = detectProvider();
        this.embeddingModel = firstNonBlank(System.getenv("EMBEDDING_MODEL"), DEFAULT_EMBEDDING_MODEL);
        String normalizedBase = (baseUrl == null || baseUrl.isBlank()) ? "" : baseUrl.trim();
        if (provider == Provider.OLLAMA) {
            String ollamaBase = firstNonBlank(
                    System.getenv("OLLAMA_BASE_URL"),
                    normalizedBase,
                    DEFAULT_OLLAMA_BASE_URL
            );
            this.chatUrl = stripRightSlash(ollamaBase) + "/v1/chat/completions";
            this.embeddingsUrl = stripRightSlash(ollamaBase) + "/api/embeddings";
        } else {
            this.chatUrl = firstNonBlank(
                    normalizedBase,
                    System.getenv("OPENAI_CHAT_URL"),
                    DEFAULT_OPENAI_CHAT_URL
            );
            this.embeddingsUrl = firstNonBlank(
                    System.getenv("OPENAI_EMBEDDINGS_URL"),
                    inferEmbeddingsUrl(this.chatUrl),
                    "https://api.deepseek.com/embeddings"
            );
        }
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
                .url(chatUrl)
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
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

    public List<Double> embedText(String text) {
        String input = truncateForEmbedding(text);
        try {
            if (provider == Provider.OLLAMA) {
                return embedByOllama(input);
            }
            return embedByOpenAI(input);
        } catch (Exception e) {
            LOG.debug("embedding 请求失败，降级 hash 向量", e);
            return fallbackEmbedding(input);
        }
    }

    private List<Double> embedByOpenAI(String text) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", embeddingModel);
        payload.put("input", text);

        Request request = new Request.Builder()
                .url(embeddingsUrl)
                .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            String body = response.body() == null ? "{}" : response.body().string();
            JsonNode root = mapper.readTree(body);
            JsonNode emb = root.path("data").path(0).path("embedding");
            return parseEmbeddingArray(emb);
        }
    }

    private List<Double> embedByOllama(String text) throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", embeddingModel);
        payload.put("prompt", text);
        Request request = new Request.Builder()
                .url(embeddingsUrl)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            String body = response.body() == null ? "{}" : response.body().string();
            JsonNode root = mapper.readTree(body);
            JsonNode emb = root.path("embedding");
            return parseEmbeddingArray(emb);
        }
    }

    private List<Double> parseEmbeddingArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return fallbackEmbedding("");
        }
        List<Double> vec = new ArrayList<>();
        for (JsonNode n : node) {
            vec.add(n.asDouble(0.0));
        }
        return vec;
    }

    private List<Double> fallbackEmbedding(String text) {
        int dims = 96;
        double[] arr = new double[dims];
        String input = text == null ? "" : text;
        for (int i = 0; i < input.length(); i++) {
            int idx = i % dims;
            arr[idx] += (input.charAt(i) % 97) / 97.0;
        }
        List<Double> vec = new ArrayList<>(dims);
        for (double v : arr) {
            vec.add(v);
        }
        return vec;
    }

    private String truncateForEmbedding(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_EMBED_TEXT_CHARS ? text : text.substring(0, MAX_EMBED_TEXT_CHARS);
    }

    private String inferEmbeddingsUrl(String chatUrl) {
        String c = chatUrl == null ? "" : chatUrl;
        if (c.endsWith("/chat/completions")) {
            return c.substring(0, c.length() - "/chat/completions".length()) + "/embeddings";
        }
        if (c.endsWith("/v1/chat/completions")) {
            return c.substring(0, c.length() - "/v1/chat/completions".length()) + "/v1/embeddings";
        }
        return c;
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private String stripRightSlash(String s) {
        if (s == null) {
            return "";
        }
        String v = s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private Provider detectProvider() {
        String raw = System.getenv("MODEL_PROVIDER");
        if (raw != null && raw.toLowerCase(Locale.ROOT).contains("ollama")) {
            return Provider.OLLAMA;
        }
        return Provider.OPENAI;
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

    private enum Provider {
        OLLAMA,
        OPENAI
    }
}
