package com.dr.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.util.List;

public class WebSearchService {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SearchProviderFactory providerFactory;
    private final NetworkPolicy networkPolicy;

    public WebSearchService(Path workspaceRoot) {
        this.providerFactory = new SearchProviderFactory(workspaceRoot);
        this.networkPolicy = new NetworkPolicy(12, 4.0);
    }

    public String search(String query, int num) {
        try {
            SearchProvider provider = providerFactory.getProvider();
            networkPolicy.acquireToken();
            List<SearchResult> results = provider.search(query, Math.max(1, Math.min(10, num)));
            ObjectNode root = MAPPER.createObjectNode();
            root.put("ok", true);
            root.put("provider", provider.name());
            ArrayNode arr = root.putArray("results");
            for (SearchResult r : results) {
                ObjectNode n = arr.addObject();
                n.put("title", r.title());
                n.put("url", r.url());
                n.put("snippet", r.snippet());
                n.put("source", r.source());
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return error("web_search_failed", e.getMessage());
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
