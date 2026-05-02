package com.dr.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class LongTermMemoryStore {
    private static final Logger LOG = LoggerFactory.getLogger(LongTermMemoryStore.class);
    private static final int MAX_ENTRIES = 500;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .findAndRegisterModules();

    private final Path filePath;

    public LongTermMemoryStore(Path workspaceRoot) {
        this.filePath = workspaceRoot.resolve(".dongrancli.memory.json").toAbsolutePath().normalize();
    }

    public synchronized List<MemoryEntry> load() {
        if (!Files.exists(filePath)) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(filePath.toFile(), new TypeReference<>() {
            });
        } catch (IOException e) {
            LOG.warn("加载长期记忆失败: {}", filePath, e);
            return new ArrayList<>();
        }
    }

    public synchronized void save(List<MemoryEntry> entries) {
        try {
            Files.createDirectories(filePath.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), entries);
        } catch (IOException e) {
            LOG.warn("保存长期记忆失败: {}", filePath, e);
        }
    }

    public synchronized void appendMemory(String userInput, String answer) {
        String content = buildMemoryContent(userInput, answer);
        if (content.isBlank()) {
            return;
        }

        List<MemoryEntry> entries = load();
        MemoryEntry entry = new MemoryEntry(
                UUID.randomUUID().toString(),
                content,
                keywords(content),
                Instant.now(),
                0
        );
        entries.add(entry);
        entries.sort(Comparator.comparing(MemoryEntry::getCreatedAt).reversed());
        if (entries.size() > MAX_ENTRIES) {
            entries = new ArrayList<>(entries.subList(0, MAX_ENTRIES));
        }
        save(entries);
    }

    private String buildMemoryContent(String userInput, String answer) {
        String u = sanitize(userInput);
        String a = sanitize(answer);
        if (u.isBlank() || a.isBlank()) {
            return "";
        }
        return "用户问题: " + u + " | 答案摘要: " + a;
    }

    private List<String> keywords(String text) {
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5\\s]", " ");
        String[] parts = cleaned.split("\\s+");
        List<String> tokens = new ArrayList<>();
        for (String p : parts) {
            if (p.length() >= 2) {
                tokens.add(p);
            }
        }
        if (tokens.size() > 12) {
            return tokens.subList(0, 12);
        }
        return tokens;
    }

    private String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replaceAll("\\s+", " ").trim();
        return v.length() <= 260 ? v : v.substring(0, 260) + "...";
    }
}
