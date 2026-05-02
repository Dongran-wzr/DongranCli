package com.dr.web.providers;

import com.dr.web.SearchProvider;
import com.dr.web.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ZhipuSearchProvider implements SearchProvider {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final String apiKey;
    private final String endpoint;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ZhipuSearchProvider(String apiKey, String endpoint) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
    }

    @Override
    public String name() {
        return "zhipu";
    }

    @Override
    public List<SearchResult> search(String query, int num) throws Exception {
        // 兼容可配置 endpoint，默认 OpenAI 兼容风格工具调用接口。
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", "glm-4-plus");
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "user").put("content", query);
        ArrayNode tools = payload.putArray("tools");
        ObjectNode t = tools.addObject();
        t.put("type", "web_search");
        payload.put("stream", false);

        Request req = new Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(payload.toString(), JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("Zhipu HTTP " + resp.code());
            }
            String body = resp.body() == null ? "{}" : resp.body().string();
            JsonNode root = mapper.readTree(body);
            List<SearchResult> out = new ArrayList<>();

            // 兼容几种响应结构。
            JsonNode refs = root.path("references");
            if (refs.isArray()) {
                for (JsonNode item : refs) {
                    out.add(new SearchResult(
                            item.path("title").asText(""),
                            item.path("url").asText(""),
                            item.path("snippet").asText(""),
                            name()
                    ));
                    if (out.size() >= num) {
                        break;
                    }
                }
            }
            if (out.isEmpty()) {
                JsonNode content = root.path("choices").path(0).path("message").path("content");
                String snippet = content.isTextual() ? content.asText("") : content.toString();
                out.add(new SearchResult("智谱搜索结果", endpoint, snippet, name()));
            }
            return out;
        }
    }
}
