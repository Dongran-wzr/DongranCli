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

public class SearxngSearchProvider implements SearchProvider {
    private final String baseUrl;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public SearxngSearchProvider(String baseUrl) {
        this.baseUrl = trimSlash(baseUrl);
    }

    @Override
    public String name() {
        return "searxng";
    }

    @Override
    public List<SearchResult> search(String query, int num) throws Exception {
        HttpUrl url = HttpUrl.parse(baseUrl + "/search").newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "json")
                .addQueryParameter("language", "zh-CN")
                .build();
        Request req = new Request.Builder().url(url).get().build();
        try (Response resp = client.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                throw new IllegalStateException("SearXNG HTTP " + resp.code());
            }
            String body = resp.body() == null ? "{}" : resp.body().string();
            JsonNode root = mapper.readTree(body);
            List<SearchResult> out = new ArrayList<>();
            for (JsonNode item : root.path("results")) {
                out.add(new SearchResult(
                        item.path("title").asText(""),
                        item.path("url").asText(""),
                        item.path("content").asText(""),
                        name()
                ));
                if (out.size() >= num) {
                    break;
                }
            }
            return out;
        }
    }

    private String trimSlash(String s) {
        String v = s == null ? "" : s.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }
}
