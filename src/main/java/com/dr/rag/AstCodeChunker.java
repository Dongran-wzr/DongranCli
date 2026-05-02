package com.dr.rag;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithSimpleName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class AstCodeChunker {
    private static final Logger LOG = LoggerFactory.getLogger(AstCodeChunker.class);
    private static final int NON_JAVA_SEGMENT_SIZE = 1600;

    static {
        StaticJavaParser.getParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
    }

    public ChunkingResult chunkWorkspace(Path workspaceRoot) {
        List<CodeChunk> chunks = new ArrayList<>();
        List<RelationEdge> relations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(workspaceRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::shouldIndex)
                    .forEach(path -> {
                        String rel = workspaceRoot.relativize(path).toString().replace('\\', '/');
                        try {
                            if (rel.endsWith(".java")) {
                                chunkJavaFile(path, rel, chunks, relations);
                            } else {
                                chunkNonJavaFile(path, rel, chunks);
                            }
                        } catch (Exception e) {
                            LOG.debug("分块失败: {}", rel, e);
                        }
                    });
        } catch (IOException e) {
            LOG.warn("遍历工作区失败", e);
        }
        return new ChunkingResult(chunks, relations);
    }

    private boolean shouldIndex(Path path) {
        String p = path.toString().toLowerCase(Locale.ROOT);
        return !p.contains("\\.git\\")
                && !p.contains("\\target\\")
                && !p.contains("\\.idea\\")
                && !p.endsWith(".class")
                && !p.endsWith(".jar");
    }

    private void chunkJavaFile(Path path, String relPath, List<CodeChunk> chunks, List<RelationEdge> relations) throws Exception {
        String code = Files.readString(path, StandardCharsets.UTF_8);
        CompilationUnit cu = StaticJavaParser.parse(code);

        chunks.add(new CodeChunk(
                idOf(relPath, "FILE", "file"),
                relPath,
                ChunkType.FILE,
                "file",
                1,
                Math.max(1, code.split("\n").length),
                truncate(code),
                keywords(code)
        ));

        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        List<String> imports = cu.findAll(ImportDeclaration.class).stream().map(i -> i.getNameAsString()).toList();

        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String clsName = qualifiedName(pkg, cls);
            int start = cls.getBegin().map(p -> p.line).orElse(1);
            int end = cls.getEnd().map(p -> p.line).orElse(start);
            chunks.add(new CodeChunk(
                    idOf(relPath, "CLASS", clsName),
                    relPath,
                    ChunkType.CLASS,
                    clsName,
                    start,
                    end,
                    truncate(cls.toString()),
                    keywords(cls.toString())
            ));
            relations.add(new RelationEdge(relPath, "contains", clsName));

            cls.getExtendedTypes().forEach(t -> relations.add(new RelationEdge(clsName, "extends", t.getNameAsString())));
            cls.getImplementedTypes().forEach(t -> relations.add(new RelationEdge(clsName, "implements", t.getNameAsString())));
            imports.forEach(i -> relations.add(new RelationEdge(clsName, "imports", i)));

            for (MethodDeclaration m : cls.getMethods()) {
                String methodName = clsName + "#" + m.getNameAsString();
                int ms = m.getBegin().map(p -> p.line).orElse(start);
                int me = m.getEnd().map(p -> p.line).orElse(ms);
                chunks.add(new CodeChunk(
                        idOf(relPath, "METHOD", methodName),
                        relPath,
                        ChunkType.METHOD,
                        methodName,
                        ms,
                        me,
                        truncate(m.toString()),
                        keywords(m.toString())
                ));
                relations.add(new RelationEdge(clsName, "contains", methodName));
                for (MethodCallExpr call : m.findAll(MethodCallExpr.class)) {
                    relations.add(new RelationEdge(methodName, "calls", call.getNameAsString()));
                }
            }
        }
    }

    private void chunkNonJavaFile(Path path, String relPath, List<CodeChunk> chunks) throws Exception {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.isBlank()) {
            return;
        }
        int idx = 0;
        int seg = 1;
        while (idx < content.length()) {
            int end = Math.min(content.length(), idx + NON_JAVA_SEGMENT_SIZE);
            String part = content.substring(idx, end);
            chunks.add(new CodeChunk(
                    idOf(relPath, "SEGMENT", "S" + seg),
                    relPath,
                    ChunkType.SEGMENT,
                    "segment_" + seg,
                    -1,
                    -1,
                    part,
                    keywords(part)
            ));
            idx = end;
            seg++;
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 4000 ? text : text.substring(0, 4000) + "...";
    }

    private List<String> keywords(String text) {
        String cleaned = text == null ? "" : text.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5_#]", " ");
        String[] parts = cleaned.toLowerCase(Locale.ROOT).split("\\s+");
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            if (p.length() >= 2) {
                out.add(p);
            }
            if (out.size() >= 30) {
                break;
            }
        }
        return out;
    }

    private String qualifiedName(String pkg, NodeWithSimpleName<?> node) {
        String name = node.getNameAsString();
        if (pkg == null || pkg.isBlank()) {
            return name;
        }
        return pkg + "." + name;
    }

    private String idOf(String path, String type, String symbol) {
        return hash(path + "|" + type + "|" + symbol);
    }

    private String hash(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
