package com.dr.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnvConfigResolver {
    private final Map<String, String> dotenv = new HashMap<>();

    public EnvConfigResolver(Path workspaceRoot) {
        loadDotenv(workspaceRoot.resolve(".env"));
    }

    public String get(String key, String defaultValue) {
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        String prop = System.getProperty(key);
        if (prop != null && !prop.isBlank()) {
            return prop.trim();
        }
        String dot = dotenv.get(key);
        if (dot != null && !dot.isBlank()) {
            return dot.trim();
        }
        return defaultValue;
    }

    private void loadDotenv(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(path);
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty() || s.startsWith("#") || !s.contains("=")) {
                    continue;
                }
                int idx = s.indexOf('=');
                String k = s.substring(0, idx).trim();
                String v = s.substring(idx + 1).trim();
                dotenv.put(k, v);
            }
        } catch (IOException ignored) {
        }
    }
}
