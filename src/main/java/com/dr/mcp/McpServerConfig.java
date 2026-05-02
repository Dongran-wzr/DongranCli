package com.dr.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record McpServerConfig(
        String name,
        String transportType, // stdio | http
        String command,
        String baseUrl,
        Map<String, String> env,
        List<String> args,
        Duration timeout
) {
    public Duration effectiveTimeout() {
        return timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(20) : timeout;
    }
}
