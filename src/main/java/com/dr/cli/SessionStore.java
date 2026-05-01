package com.dr.cli;

import com.dr.agent.Message;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class SessionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path sessionFile;

    public SessionStore(Path workspaceRoot) {
        this.sessionFile = workspaceRoot.resolve(".dongrancli.session.json").toAbsolutePath().normalize();
    }

    public void save(List<Message> messages) {
        try {
            Files.createDirectories(sessionFile.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(sessionFile.toFile(), messages);
        } catch (IOException ignored) {
        }
    }

    public List<Message> load() {
        if (!Files.exists(sessionFile)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(sessionFile.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            return List.of();
        }
    }

    public void clear() {
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException ignored) {
        }
    }
}
