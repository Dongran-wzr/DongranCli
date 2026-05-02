package com.dr.web.providers;

import com.dr.web.SearchProvider;
import com.dr.web.SearchResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SerpApiSearchProvider implements SearchProvider {
    private final String apiKey;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public SerpApiSearchProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String name() {
        return "serpapi";
    }

    @Override
    public List<SearchResult> search(String query, int num) throws Exception {
        HttpUrl url = HttpUrl.parse("https://serpapi.com/search.json").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("num", String.valueOf(Math.max(1, Math.min(10, num))))
                .addQueryParameter("api_key", apiKey)
                .build();
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("SerpAPI HTTP " + resp.code());
            }
            String body = resp.body() == null ? "{}" : resp.body().string();
            JsonNode root = mapper.readTree(body);
            List<SearchResult> out = new ArrayList<>();
            for (JsonNode item : root.path("organic_results")) {
                out.add(new SearchResult(
                        item.path("title").asText(""),
                        item.path("link").asText(""),
                        item.path("snippet").asText(""),
                        name()
                ));
                if (out.size() >= num) {
                    break;
                }
            }
            return out;
        }
    }
}
