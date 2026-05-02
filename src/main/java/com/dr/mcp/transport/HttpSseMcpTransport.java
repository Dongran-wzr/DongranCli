package com.dr.mcp.transport;

import com.dr.mcp.McpServerConfig;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class HttpSseMcpTransport implements McpTransport {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final McpServerConfig config;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build();
    private final ExecutorService sseExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running;

    public HttpSseMcpTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect(Consumer<String> inboundConsumer) {
        running = true;
        sseExecutor.submit(() -> {
            Request req = new Request.Builder()
                    .url(trimSlash(config.baseUrl()) + "/sse")
                    .get()
                    .build();
            try (Response response = client.newCall(req).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    return;
                }
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8)
                );
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        String data = line.substring("data:".length()).trim();
                        if (!data.isBlank()) {
                            inboundConsumer.accept(data);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void send(String message) throws Exception {
        Request req = new Request.Builder()
                .url(trimSlash(config.baseUrl()) + "/rpc")
                .post(RequestBody.create(message, JSON))
                .build();
        try (Response response = client.newCall(req).execute()) {
            if (!response.isSuccessful()) {
                throw new IllegalStateException("HTTP RPC failed: " + response.code());
            }
        }
    }

    @Override
    public String name() {
        return "streamable-http";
    }

    @Override
    public void close() {
        running = false;
        sseExecutor.shutdownNow();
    }

    private String trimSlash(String v) {
        String s = v == null ? "" : v.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
