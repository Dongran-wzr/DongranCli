package com.dr.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

public class CliConfig {
    private final String apiKey;
    private final String model;
    private final String apiUrl;

    public CliConfig(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
    }

    public String apiKey() {
        return apiKey;
    }

    public String model() {
        return model;
    }

    public String apiUrl() {
        return apiUrl;
    }

    public static CliConfig load(Path workspaceRoot) {
        Properties props = new Properties();
        merge(props, workspaceRoot.resolve(".dongrancli.properties"));
        merge(props, Path.of(System.getProperty("user.home"), ".dongrancli", "config.properties"));
        mergeEnvFile(props, workspaceRoot.resolve(".env"));

        String apiKey = firstNonBlank(
                System.getenv("DEEPSEEK_API_KEY"),
                System.getenv("GLM_API_KEY"),
                props.getProperty("api.key")
        );
        String model = firstNonBlank(
                System.getenv("DONGRAN_MODEL"),
                props.getProperty("model"),
                "deepseek-chat"
        );
        String apiUrl = firstNonBlank(
                System.getenv("DONGRAN_API_URL"),
                props.getProperty("api.url"),
                "https://api.deepseek.com/chat/completions"
        );
        return new CliConfig(apiKey, model, apiUrl);
    }

    private static void merge(Properties target, Path file) {
        if (!Files.exists(file)) {
            return;
        }
        try (InputStream in = Files.newInputStream(file)) {
            Properties p = new Properties();
            p.load(in);
            for (String k : p.stringPropertyNames()) {
                target.setProperty(k, p.getProperty(k));
            }
        } catch (IOException ignored) {
        }
    }

    private static void mergeEnvFile(Properties target, Path envFile) {
        if (!Files.exists(envFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(envFile)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
                    continue;
                }
                int split = trimmed.indexOf('=');
                String key = trimmed.substring(0, split).trim();
                String value = trimmed.substring(split + 1).trim();
                if ("GLM_API_KEY".equals(key) || "DEEPSEEK_API_KEY".equals(key)) {
                    target.setProperty("api.key", value);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "CliConfig{" +
                "apiKey=" + (Objects.nonNull(apiKey) ? "***已配置***" : "<未配置>") +
                ", model='" + model + '\'' +
                ", apiUrl='" + apiUrl + '\'' +
                '}';
    }
}
