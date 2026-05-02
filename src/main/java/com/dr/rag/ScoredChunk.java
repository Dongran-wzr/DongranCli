package com.dr.rag;

public record ScoredChunk(
        CodeChunk chunk,
        double semanticScore,
        double lexicalScore,
        double typeBoost,
        double finalScore
) {
}
