package com.dr.rag;

import com.dr.llm.DSV4Client;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SearchCodeService {
    private static final Logger LOG = LoggerFactory.getLogger(SearchCodeService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workspaceRoot;
    private final DSV4Client llmClient;
    private final AstCodeChunker chunker;
    private final SQLiteVectorStore vectorStore;
    private final HybridRetriever hybridRetriever;
    private final CodeRelationGraph relationGraph;

    public SearchCodeService(Path workspaceRoot, DSV4Client llmClient) {
        this.workspaceRoot = workspaceRoot;
        this.llmClient = llmClient;
        this.chunker = new AstCodeChunker();
        this.vectorStore = new SQLiteVectorStore(workspaceRoot);
        this.hybridRetriever = new HybridRetriever();
        this.relationGraph = new CodeRelationGraph();
    }

    public String search(String query, int topK) {
        try {
            refreshIndex();
            List<Double> queryEmbedding = llmClient.embedText(query);
            List<SQLiteVectorStore.StoredChunk> all = vectorStore.loadAllChunks();
            List<ScoredChunk> found = hybridRetriever.retrieve(query, queryEmbedding, all, Math.max(1, topK));

            String callChainClass = hybridRetriever.detectCallChainClass(query);
            String callChain = "";
            if (!callChainClass.isBlank()) {
                callChain = relationGraph.queryCallChain(callChainClass, 4);
            }
            return render(query, found, callChain);
        } catch (Exception e) {
            LOG.warn("search_code 执行失败", e);
            return error("search_failed", e.getMessage());
        }
    }

    private void refreshIndex() {
        ChunkingResult result = chunker.chunkWorkspace(workspaceRoot);
        List<SQLiteVectorStore.StoredChunk> rows = new ArrayList<>();
        for (CodeChunk c : result.chunks()) {
            List<Double> embedding = llmClient.embedText(c.content());
            rows.add(new SQLiteVectorStore.StoredChunk(c, embedding));
        }
        vectorStore.replaceAll(rows, result.relations());
        relationGraph.rebuild(result.relations());
    }

    private String render(String query, List<ScoredChunk> found, String callChain) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("ok", true);
            root.put("query", query);
            ArrayNode arr = root.putArray("results");
            for (ScoredChunk s : found) {
                ObjectNode n = arr.addObject();
                n.put("path", s.chunk().path());
                n.put("type", s.chunk().chunkType().name());
                n.put("symbol", s.chunk().symbol());
                n.put("startLine", s.chunk().startLine());
                n.put("endLine", s.chunk().endLine());
                n.put("score", round3(s.finalScore()));
                n.put("semantic", round3(s.semanticScore()));
                n.put("lexical", round3(s.lexicalScore()));
                n.put("snippet", truncate(s.chunk().content(), 320));
            }
            if (callChain != null && !callChain.isBlank()) {
                root.put("callChain", callChain);
            }
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return okFallback(Map.of("query", query, "results", found.size()));
        }
    }

    private String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text == null ? "" : text;
        }
        return text.substring(0, max) + "...";
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private String error(String code, String message) {
        try {
            return MAPPER.writeValueAsString(Map.of("ok", false, "code", code, "message", message == null ? "" : message));
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":\"unknown\"}";
        }
    }

    private String okFallback(Map<String, Object> data) {
        try {
            return MAPPER.writeValueAsString(Map.of("ok", true, "data", data));
        } catch (Exception e) {
            return "{\"ok\":true}";
        }
    }
}
