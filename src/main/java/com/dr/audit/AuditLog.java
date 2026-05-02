package com.dr.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

public class AuditLog {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path logFile;

    public AuditLog(Path workspaceRoot) {
        this.logFile = workspaceRoot.resolve(".dongran_audit.jsonl").toAbsolutePath().normalize();
    }

    public synchronized void log(String category, String action, String detail) {
        try {
            Files.createDirectories(logFile.getParent());
            ObjectNode node = MAPPER.createObjectNode();
            node.put("ts", Instant.now().toString());
            node.put("category", category);
            node.put("action", action);
            node.put("detail", detail == null ? "" : detail);
            Files.writeString(
                    logFile,
                    node.toString() + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (Exception ignored) {
        }
    }
}
