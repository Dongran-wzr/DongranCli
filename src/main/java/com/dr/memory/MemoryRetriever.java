package com.dr.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MemoryRetriever {
    public List<MemoryEntry> retrieveTopK(List<MemoryEntry> all, String query, int k) {
        Set<String> q = tokenize(query);
        if (q.isEmpty()) {
            return List.of();
        }
        List<ScoredMemory> scored = new ArrayList<>();
        for (MemoryEntry entry : all) {
            Set<String> m = tokenize(entry.getContent() + " " + String.join(" ", entry.getKeywords()));
            int overlap = 0;
            for (String token : q) {
                if (m.contains(token)) {
                    overlap++;
                }
            }
            double score = overlap + Math.min(entry.getHits(), 5) * 0.1;
            if (score > 0) {
                scored.add(new ScoredMemory(entry, score));
            }
        }

        scored.sort(Comparator.<ScoredMemory>comparingDouble(ScoredMemory::score).reversed());
        List<MemoryEntry> result = new ArrayList<>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            MemoryEntry e = scored.get(i).entry();
            e.touch();
            result.add(e);
        }
        return result;
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String cleaned = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5\\s]", " ");
        String[] parts = cleaned.split("\\s+");
        Set<String> set = new HashSet<>();
        for (String p : parts) {
            if (p.length() >= 2) {
                set.add(p);
            }
        }
        return set;
    }

    private record ScoredMemory(MemoryEntry entry, double score) {
    }
}
