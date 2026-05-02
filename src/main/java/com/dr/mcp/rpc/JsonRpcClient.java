package com.dr.mcp.rpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AtomicLong idGen = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Consumer<String> outboundSender;
    private volatile Consumer<JsonNode> notificationHandler = n -> {
    };

    public JsonRpcClient(Consumer<String> outboundSender) {
        this.outboundSender = outboundSender;
    }

    public void setNotificationHandler(Consumer<JsonNode> notificationHandler) {
        this.notificationHandler = notificationHandler == null ? n -> {
        } : notificationHandler;
    }

    public CompletableFuture<JsonNode> request(String method, JsonNode params, Duration timeout) {
        String id = String.valueOf(idGen.getAndIncrement());
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduleTimeout(id, future, timeout == null ? Duration.ofSeconds(20) : timeout);
        send(req);
        return future;
    }

    public void notify(String method, JsonNode params) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        send(req);
    }

    public void broadcastNotification(String method, JsonNode params, Iterable<Consumer<ObjectNode>> sinks) {
        ObjectNode req = MAPPER.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        for (Consumer<ObjectNode> sink : sinks) {
            sink.accept(req.deepCopy());
        }
    }

    public void onInboundMessage(String jsonLine) {
        try {
            JsonNode node = MAPPER.readTree(jsonLine);
            if (node.has("id")) {
                String id = node.get("id").asText();
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    future.complete(node);
                }
                return;
            }
            if (node.has("method")) {
                notificationHandler.accept(node);
            }
        } catch (Exception ignored) {
        }
    }

    public void close() {
        for (Map.Entry<String, CompletableFuture<JsonNode>> e : pending.entrySet()) {
            e.getValue().completeExceptionally(new IllegalStateException("rpc client closing"));
        }
        pending.clear();
        timeoutScheduler.shutdownNow();
    }

    private void scheduleTimeout(String id, CompletableFuture<JsonNode> future, Duration timeout) {
        timeoutScheduler.schedule(() -> {
            CompletableFuture<JsonNode> f = pending.remove(id);
            if (f != null && !f.isDone()) {
                f.completeExceptionally(new RuntimeException("JSON-RPC 请求超时: " + id));
            }
        }, Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void send(ObjectNode req) {
        try {
            outboundSender.accept(req.toString());
        } catch (Exception e) {
            if (req.has("id")) {
                String id = req.get("id").asText();
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future != null) {
                    future.completeExceptionally(e);
                }
            }
        }
    }
}
