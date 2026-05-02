package com.dr.rag;

import com.huaban.analysis.jieba.JiebaSegmenter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HybridRetriever {
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]*)\\b");
    private final JiebaSegmenter jieba = new JiebaSegmenter();

    public List<ScoredChunk> retrieve(
            String query,
            List<Double> queryEmbedding,
            List<SQLiteVectorStore.StoredChunk> allChunks,
            int topK
    ) {
        List<String> queryTokens = tokenize(query);
        Map<String, Integer> perFileCounter = new HashMap<>();
        List<ScoredChunk> scored = new ArrayList<>();
        for (SQLiteVectorStore.StoredChunk row : allChunks) {
            CodeChunk c = row.chunk();
            double semantic = cosine(queryEmbedding, row.embedding());
            double lexical = lexicalScore(queryTokens, c.content(), c.keywords());
            double typeBoost = typeBoost(query, c.chunkType());
            double finalScore = semantic * 0.72 + lexical * 0.23 + typeBoost;
            if (finalScore > 0.04) {
                scored.add(new ScoredChunk(c, semantic, lexical, typeBoost, finalScore));
            }
        }
        scored.sort((a, b) -> Double.compare(b.finalScore(), a.finalScore()));

        List<ScoredChunk> out = new ArrayList<>();
        for (ScoredChunk s : scored) {
            int used = perFileCounter.getOrDefault(s.chunk().path(), 0);
            if (used >= 4) { // 同文件限流
                continue;
            }
            out.add(s);
            perFileCounter.put(s.chunk().path(), used + 1);
            if (out.size() >= topK) {
                break;
            }
        }
        return out;
    }

    public String detectCallChainClass(String query) {
        String q = query == null ? "" : query;
        if (!(q.contains("调用链") || q.toLowerCase(Locale.ROOT).contains("call chain"))) {
            return "";
        }
        Matcher m = CLASS_NAME_PATTERN.matcher(q);
        String cls = "";
        while (m.find()) {
            cls = m.group(1);
        }
        return cls;
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        try {
            List<String> seg = jieba.sentenceProcess(text);
            for (String s : seg) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (t.length() >= 2) {
                    out.add(t);
                }
            }
        } catch (Exception ignored) {
        }
        // 兜底英文词。
        String[] english = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_\\u4e00-\\u9fa5\\s]", " ").split("\\s+");
        for (String e : english) {
            if (e.length() >= 2) {
                out.add(e);
            }
        }
        return out;
    }

    private double lexicalScore(List<String> queryTokens, String content, List<String> keywords) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        Set<String> bag = new HashSet<>();
        String normalized = (content == null ? "" : content).toLowerCase(Locale.ROOT);
        bag.addAll(keywords);
        for (String q : queryTokens) {
            if (normalized.contains(q)) {
                bag.add(q);
            }
        }
        int hit = 0;
        for (String q : queryTokens) {
            if (bag.contains(q)) {
                hit++;
            }
        }
        return (double) hit / (double) queryTokens.size();
    }

    private double typeBoost(String query, ChunkType type) {
        String q = query == null ? "" : query;
        if ((q.contains("类") || q.toLowerCase(Locale.ROOT).contains("class")) && type == ChunkType.CLASS) {
            return 0.09;
        }
        if ((q.contains("方法") || q.toLowerCase(Locale.ROOT).contains("method")) && type == ChunkType.METHOD) {
            return 0.09;
        }
        if ((q.contains("文件") || q.toLowerCase(Locale.ROOT).contains("file")) && type == ChunkType.FILE) {
            return 0.05;
        }
        return 0;
    }

    private double cosine(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int n = Math.min(a.size(), b.size());
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            double x = a.get(i);
            double y = b.get(i);
            dot += x * y;
            na += x * x;
            nb += y * y;
        }
        if (na <= 0 || nb <= 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
