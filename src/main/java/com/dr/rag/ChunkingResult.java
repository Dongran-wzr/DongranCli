package com.dr.rag;

import java.util.ArrayList;
import java.util.List;

public record ChunkingResult(List<CodeChunk> chunks, List<RelationEdge> relations) {
    public ChunkingResult {
        chunks = chunks == null ? List.of() : new ArrayList<>(chunks);
        relations = relations == null ? List.of() : new ArrayList<>(relations);
    }
}
