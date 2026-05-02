package com.dr.web;

public record SearchResult(
        String title,
        String url,
        String snippet,
        String source
) {
}
