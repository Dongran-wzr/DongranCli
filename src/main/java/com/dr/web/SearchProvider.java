package com.dr.web;

import java.util.List;

public interface SearchProvider {
    String name();

    List<SearchResult> search(String query, int num) throws Exception;
}
