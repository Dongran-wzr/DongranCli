package com.dr.rag;

import java.util.ArrayList;
import java.util.List;

public class CodeChunk {
    private final String id;
    private final String path;
    private final ChunkType chunkType;
    private final String symbol;
    private final int startLine;
    private final int endLine;
    private final String content;
    private final List<String> keywords;

    public CodeChunk(
            String id,
            String path,
            ChunkType chunkType,
            String symbol,
            int startLine,
            int endLine,
            String content,
            List<String> keywords
    ) {
        this.id = id;
        this.path = path;
        this.chunkType = chunkType;
        this.symbol = symbol == null ? "" : symbol;
        this.startLine = startLine;
        this.endLine = endLine;
        this.content = content == null ? "" : content;
        this.keywords = keywords == null ? List.of() : new ArrayList<>(keywords);
    }

    public String id() {
        return id;
    }

    public String path() {
        return path;
    }

    public ChunkType chunkType() {
        return chunkType;
    }

    public String symbol() {
        return symbol;
    }

    public int startLine() {
        return startLine;
    }

    public int endLine() {
        return endLine;
    }

    public String content() {
        return content;
    }

    public List<String> keywords() {
        return List.copyOf(keywords);
    }
}
