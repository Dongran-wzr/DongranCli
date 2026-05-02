package com.dr.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.util.concurrent.TimeUnit;

public class WebFetchService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NetworkPolicy networkPolicy = new NetworkPolicy(10, 3.0);
    private final ReadabilityExtractor readabilityExtractor = new ReadabilityExtractor();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build();

    public String fetch(String rawUrl) {
        try {
            String url = normalizeUrl(rawUrl);
            networkPolicy.assertAllowed(url);
            networkPolicy.acquireToken();

            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "DongranCli/1.0")
                    .get()
                    .build();
            try (Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    return error("http_error", "HTTP " + resp.code());
                }
                String html = resp.body() == null ? "" : resp.body().string();
                String text = readabilityExtractor.extract(html, url);
                ObjectNode root = MAPPER.createObjectNode();
                root.put("ok", true);
                root.put("url", url);
                root.put("title", extractTitle(html, url));
                root.put("content", text);
                return MAPPER.writeValueAsString(root);
            }
        } catch (Exception e) {
            return error("web_fetch_failed", e.getMessage());
        }
    }

    private String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("url 不能为空");
        }
        String s = raw.trim();
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        if (s.startsWith("http://")) {
            s = "https://" + s.substring("http://".length());
        }
        return s;
    }

    private String extractTitle(String html, String baseUrl) {
        try {
            org.jsoup.nodes.Document doc = org.jsoup.Jsoup.parse(html, baseUrl);
            return doc.title();
        } catch (Exception e) {
            return "";
        }
    }

    private String error(String code, String message) {
        try {
            return MAPPER.writeValueAsString(
                    MAPPER.createObjectNode()
                            .put("ok", false)
                            .put("code", code)
                            .put("message", message == null ? "" : message)
            );
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":\"unknown\"}";
        }
    }
}
