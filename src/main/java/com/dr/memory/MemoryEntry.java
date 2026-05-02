package com.dr.memory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class MemoryEntry {
    private String id;
    private String content;
    private List<String> keywords;
    private Instant createdAt;
    private int hits;

    public MemoryEntry() {
    }

    public MemoryEntry(String id, String content, List<String> keywords, Instant createdAt, int hits) {
        this.id = id;
        this.content = content;
        this.keywords = keywords == null ? List.of() : new ArrayList<>(keywords);
        this.createdAt = createdAt;
        this.hits = hits;
    }

    public String getId() {
        return id;
    }

    public String getContent() {
        return content;
    }

    public List<String> getKeywords() {
        return keywords == null ? List.of() : List.copyOf(keywords);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getHits() {
        return hits;
    }

    public void touch() {
        hits++;
    }
}
