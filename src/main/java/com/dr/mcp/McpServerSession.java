package com.dr.mcp;

import com.dr.audit.AuditLog;
import com.dr.mcp.rpc.JsonRpcClient;
import com.dr.mcp.transport.HttpSseMcpTransport;
import com.dr.mcp.transport.McpTransport;
import com.dr.mcp.transport.StdioMcpTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class McpServerSession implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerConfig config;
    private final McpTransport transport;
    private final JsonRpcClient rpcClient;
    private final AuditLog auditLog;
    private volatile boolean initialized;

    public McpServerSession(McpServerConfig config, AuditLog auditLog) {
        this.config = config;
        this.auditLog = auditLog;
        this.transport = createTransport(config);
        this.rpcClient = new JsonRpcClient(msg -> {
            try {
                transport.send(msg);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public String serverName() {
        return config.name();
    }

    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                transport.connect(rpcClient::onInboundMessage);
                initializeHandshake();
                initialized = true;
                auditLog.log("mcp", "server_started", config.name() + " via " + transport.name());
            } catch (Exception e) {
                auditLog.log("mcp", "server_start_failed", config.name() + ": " + e.getMessage());
                throw new RuntimeException(e);
            }
        });
    }

    public List<McpToolDefinition> listTools() {
        ensureInitialized();
        try {
            JsonNode resp = rpcClient.request("tools/list", MAPPER.createObjectNode(), config.effectiveTimeout()).join();
            if (resp.has("error")) {
                throw new IllegalStateException(resp.get("error").toString());
            }
            List<McpToolDefinition> out = new ArrayList<>();
            JsonNode tools = resp.path("result").path("tools");
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    out.add(new McpToolDefinition(
                            t.path("name").asText(""),
                            t.path("description").asText(""),
                            t.path("inputSchema")
                    ));
                }
            }
            return out;
        } catch (Exception e) {
            auditLog.log("mcp", "tools_list_failed", config.name() + ": " + e.getMessage());
            return List.of();
        }
    }

    public String callTool(String toolName, String argumentsJson) {
        ensureInitialized();
        try {
            JsonNode args = argumentsJson == null || argumentsJson.isBlank()
                    ? MAPPER.createObjectNode()
                    : MAPPER.readTree(argumentsJson);
            ObjectNode params = MAPPER.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", args);
            JsonNode resp = rpcClient.request("tools/call", params, config.effectiveTimeout()).join();
            auditLog.log("mcp", "tool_call", config.name() + "::" + toolName);
            if (resp.has("error")) {
                return error("mcp_tool_error", resp.get("error").toString());
            }
            JsonNode result = resp.path("result");
            return result.isMissingNode() ? "{\"ok\":true}" : result.toString();
        } catch (Exception e) {
            auditLog.log("mcp", "tool_call_failed", config.name() + "::" + toolName + " -> " + e.getMessage());
            return error("mcp_call_failed", e.getMessage());
        }
    }

    private void initializeHandshake() {
        Duration timeout = config.effectiveTimeout();
        ObjectNode initParams = MAPPER.createObjectNode();
        initParams.put("protocolVersion", "2024-11-05");
        ObjectNode capabilities = initParams.putObject("capabilities");
        capabilities.putObject("tools");
        initParams.putObject("clientInfo")
                .put("name", "DongranCli")
                .put("version", "1.0.0");

        JsonNode initResp = rpcClient.request("initialize", initParams, timeout).join();
        if (initResp.has("error")) {
            throw new IllegalStateException("initialize failed: " + initResp.get("error"));
        }

        ObjectNode notifyParams = MAPPER.createObjectNode();
        notifyParams.putObject("clientInfo")
                .put("name", "DongranCli")
                .put("version", "1.0.0");
        rpcClient.notify("notifications/initialized", notifyParams);

        ObjectNode capReq = MAPPER.createObjectNode();
        capReq.set("capabilities", capabilities);
        rpcClient.request("capabilities", capReq, timeout).handle((ok, ex) -> null).join();
    }

    private McpTransport createTransport(McpServerConfig cfg) {
        if ("http".equalsIgnoreCase(cfg.transportType())) {
            return new HttpSseMcpTransport(cfg);
        }
        return new StdioMcpTransport(cfg);
    }

    private void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException("MCP server not initialized: " + config.name());
        }
    }

    private String error(String code, String message) {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("ok", false);
        n.put("code", code);
        n.put("message", message == null ? "" : message);
        return n.toString();
    }

    @Override
    public void close() throws Exception {
        rpcClient.close();
        transport.close();
    }
}
