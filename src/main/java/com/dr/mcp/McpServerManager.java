package com.dr.mcp;

import com.dr.audit.AuditLog;
import com.dr.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McpServerManager implements AutoCloseable {
    private final AuditLog auditLog;
    private final ExecutorService startupPool = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, McpServerSession> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean started;

    public McpServerManager(Path workspaceRoot) {
        this.auditLog = new AuditLog(workspaceRoot);
    }

    public void startAll(List<McpServerConfig> configs, ToolRegistry registry) {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (McpServerConfig cfg : configs) {
            CompletableFuture<Void> f = CompletableFuture
                    .runAsync(() -> startOne(cfg, registry), startupPool)
                    .exceptionally(ex -> null); // 单个 server 失败不影响全局
            futures.add(f);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        started = true;
    }

    public boolean isStarted() {
        return started;
    }

    public String status() {
        return "MCP servers: " + sessions.keySet();
    }

    private void startOne(McpServerConfig cfg, ToolRegistry registry) {
        McpServerSession session = new McpServerSession(cfg, auditLog);
        session.start().join();
        sessions.put(cfg.name(), session);
        List<McpToolDefinition> tools = session.listTools();
        for (McpToolDefinition t : tools) {
            String namespaced = namespace(cfg.name(), t.name());
            registry.registerTool(new ToolRegistry.RegisteredTool(
                    new ToolRegistry.ToolDefinition(
                            namespaced,
                            "[MCP:" + cfg.name() + "] " + t.description(),
                            t.inputSchema()
                    ),
                    args -> {
                        String argsJson = mapper.writeValueAsString(args);
                        return session.callTool(t.name(), argsJson);
                    }
            ));
            auditLog.log("mcp", "tool_registered", namespaced);
        }
    }

    public static List<McpServerConfig> parseFromEnv() {
        String raw = System.getenv("MCP_SERVERS");
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        // 格式: name|stdio|cmd|arg1,arg2;name2|http|https://host
        List<McpServerConfig> out = new ArrayList<>();
        String[] parts = raw.split(";");
        for (String part : parts) {
            String s = part.trim();
            if (s.isBlank()) {
                continue;
            }
            String[] cells = s.split("\\|");
            if (cells.length < 3) {
                continue;
            }
            String name = cells[0].trim();
            String transport = cells[1].trim();
            if ("http".equalsIgnoreCase(transport)) {
                out.add(new McpServerConfig(name, "http", "", cells[2].trim(), Map.of(), List.of(), Duration.ofSeconds(20)));
            } else {
                String cmd = cells[2].trim();
                List<String> args = List.of();
                if (cells.length > 3 && !cells[3].isBlank()) {
                    args = List.of(cells[3].split(","));
                }
                out.add(new McpServerConfig(name, "stdio", cmd, "", Map.of(), args, Duration.ofSeconds(20)));
            }
        }
        return out;
    }

    private String namespace(String server, String tool) {
        return "mcp__" + server + "__" + tool;
    }

    @Override
    public void close() {
        sessions.values().forEach(s -> {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        });
        startupPool.shutdownNow();
    }
}
