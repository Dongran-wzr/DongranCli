package com.dr.rag;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CodeRelationGraph {
    private final Map<String, List<RelationEdge>> adjacency = new HashMap<>();

    public void rebuild(List<RelationEdge> edges) {
        adjacency.clear();
        for (RelationEdge edge : edges) {
            adjacency.computeIfAbsent(edge.src(), k -> new ArrayList<>()).add(edge);
        }
    }

    public String queryCallChain(String className, int maxDepth) {
        if (className == null || className.isBlank()) {
            return "未提供类名";
        }
        ArrayDeque<NodeDepth> q = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        q.add(new NodeDepth(className, 0));
        visited.add(className);
        List<String> lines = new ArrayList<>();
        lines.add("调用链查询起点: " + className);

        while (!q.isEmpty()) {
            NodeDepth cur = q.removeFirst();
            if (cur.depth > maxDepth) {
                continue;
            }
            List<RelationEdge> out = adjacency.getOrDefault(cur.node, List.of());
            for (RelationEdge e : out) {
                if (!"contains".equals(e.relationType()) && !"calls".equals(e.relationType())) {
                    continue;
                }
                lines.add("  ".repeat(cur.depth + 1) + cur.node + " -" + e.relationType() + "-> " + e.dst());
                if (visited.add(e.dst())) {
                    q.add(new NodeDepth(e.dst(), cur.depth + 1));
                }
            }
        }
        if (lines.size() == 1) {
            lines.add("未找到调用链");
        }
        return String.join(System.lineSeparator(), lines);
    }

    private record NodeDepth(String node, int depth) {
    }
}
