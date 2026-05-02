package com.dr.web;

import com.dr.web.providers.SearxngSearchProvider;
import com.dr.web.providers.SerpApiSearchProvider;
import com.dr.web.providers.ZhipuSearchProvider;

import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

public class SearchProviderFactory {
    private final EnvConfigResolver config;
    private final AtomicReference<SearchProvider> activeProvider = new AtomicReference<>();
    private volatile String activeName = "";

    public SearchProviderFactory(Path workspaceRoot) {
        this.config = new EnvConfigResolver(workspaceRoot);
    }

    public SearchProvider getProvider() {
        String desired = config.get("SEARCH_PROVIDER", "").trim().toLowerCase(Locale.ROOT);
        if (desired.isBlank()) {
            desired = autoDetectProvider();
        }
        if (!desired.equals(activeName) || activeProvider.get() == null) {
            activeProvider.set(create(desired));
            activeName = desired;
        }
        return activeProvider.get();
    }

    public void forceSwitch(String providerName) {
        String p = providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
        activeProvider.set(create(p));
        activeName = p;
    }

    private SearchProvider create(String name) {
        return switch (name) {
            case "serpapi" -> new SerpApiSearchProvider(config.get("SERPAPI_API_KEY", ""));
            case "zhipu" -> new ZhipuSearchProvider(
                    config.get("ZHIPU_API_KEY", ""),
                    config.get("ZHIPU_SEARCH_URL", "https://open.bigmodel.cn/api/paas/v4/chat/completions")
            );
            case "searxng" -> new SearxngSearchProvider(
                    config.get("SEARXNG_BASE_URL", "https://searx.be")
            );
            default -> new SearxngSearchProvider(
                    config.get("SEARXNG_BASE_URL", "https://searx.be")
            );
        };
    }

    private String autoDetectProvider() {
        if (!config.get("ZHIPU_API_KEY", "").isBlank()) {
            return "zhipu";
        }
        if (!config.get("SERPAPI_API_KEY", "").isBlank()) {
            return "serpapi";
        }
        return "searxng";
    }
}
