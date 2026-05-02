package com.dr.tool;

import com.dr.llm.DSV4Client;
import com.dr.rag.SearchCodeService;
import com.dr.web.WebFetchService;
import com.dr.web.WebSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ToolRegistry {
    private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);
    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(20);
    private static final List<String> COMMAND_ALLOWLIST = List.of(
            "dir",
            "type",
            "findstr",
            "git status",
            "git diff",
            "java -version"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RegisteredTool> tools = new ConcurrentHashMap<>();
    private final Path workspaceRoot;
    private final ExecutorService processExecutor = Executors.newCachedThreadPool();
    private final SearchCodeService searchCodeService;
    private final WebSearchService webSearchService;
    private final WebFetchService webFetchService;

    public ToolRegistry(Path workspaceRoot) {
        this.workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
        this.searchCodeService = new SearchCodeService(this.workspaceRoot, createInternalLlmClient());
        this.webSearchService = new WebSearchService(this.workspaceRoot);
        this.webFetchService = new WebFetchService();
        registerBuiltInTools();
        loadExternalProviders();
    }

    public record ToolDefinition(String name, String description, JsonNode parameters) {
    }

    @FunctionalInterface
    public interface ToolExecutor {
        String execute(Map<String, String> args) throws Exception;
    }

    public record RegisteredTool(ToolDefinition definition, ToolExecutor executor) {
    }

    private record Param(String name, String type, String description, boolean required) {
    }

    public List<ToolDefinition> getToolDefinitions() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }

    public String executeTool(String toolName, String argumentsJson) {
        RegisteredTool tool = tools.get(toolName);
        if (tool == null) {
            return error("unknown_tool", "未找到工具: " + toolName);
        }
        try {
            Map<String, String> args = parseArgs(argumentsJson);
            return tool.executor().execute(args);
        } catch (Exception e) {
            LOG.warn("工具执行失败: {}", toolName, e);
            return error("tool_execution_failed", e.getMessage());
        }
    }

    public void registerTool(RegisteredTool tool) {
        tools.put(tool.definition().name(), tool);
    }

    private void registerBuiltInTools() {
        registerReadFile();
        registerWriteFile();
        registerListDir();
        registerRunCommand();
        registerSearchCode();
        registerWebSearch();
        registerWebFetch();
    }

    private void registerReadFile() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "read_file",
                        "读取工作区内文件内容",
                        createParameters(new Param("path", "string", "工作区内文件路径", true))
                ),
                args -> {
                    Path resolved = safeResolve(args.get("path"));
                    String content = Files.readString(resolved, StandardCharsets.UTF_8);
                    return ok(Map.of(
                            "path", resolved.toString(),
                            "content", content
                    ));
                }
        ));
    }

    private void registerWriteFile() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "write_file",
                        "写入工作区内文件内容",
                        createParameters(
                                new Param("path", "string", "工作区内文件路径", true),
                                new Param("content", "string", "写入内容", true)
                        )
                ),
                args -> {
                    Path resolved = safeResolve(args.get("path"));
                    Files.createDirectories(resolved.getParent());
                    Files.writeString(resolved, args.getOrDefault("content", ""), StandardCharsets.UTF_8);
                    return ok(Map.of("path", resolved.toString(), "bytes", Files.size(resolved)));
                }
        ));
    }

    private void registerListDir() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "list_dir",
                        "列出工作区目录内容",
                        createParameters(new Param("path", "string", "工作区目录路径，默认 .", false))
                ),
                args -> {
                    String pathArg = args.getOrDefault("path", ".");
                    Path resolved = safeResolve(pathArg);
                    if (!Files.isDirectory(resolved)) {
                        return error("not_directory", "目标不是目录: " + resolved);
                    }
                    List<String> entries;
                    try (var stream = Files.list(resolved)) {
                        entries = stream
                                .map(p -> p.getFileName().toString())
                                .sorted()
                                .toList();
                    }
                    return ok(Map.of("path", resolved.toString(), "entries", entries));
                }
        ));
    }

    private void registerRunCommand() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "run_command",
                        "执行安全白名单内命令",
                        createParameters(new Param("command", "string", "要执行的命令", true))
                ),
                args -> {
                    String command = args.getOrDefault("command", "").trim();
                    if (!isAllowedCommand(command)) {
                        return error("command_rejected", "命令不在允许列表中");
                    }
                    return runCommandWithTimeout(command);
                }
        ));
    }

    private void registerSearchCode() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "search_code",
                        "在代码库中进行语义+关键词混合检索，返回相关代码片段与调用链",
                        createParameters(
                                new Param("query", "string", "自然语言查询，如“找 UserService 调用链”", true),
                                new Param("top_k", "string", "返回结果数，默认 8", false)
                        )
                ),
                args -> {
                    String query = args.getOrDefault("query", "").trim();
                    if (query.isBlank()) {
                        return error("invalid_query", "query 不能为空");
                    }
                    int topK = 8;
                    String topKRaw = args.get("top_k");
                    if (topKRaw != null && !topKRaw.isBlank()) {
                        try {
                            topK = Math.max(1, Math.min(20, Integer.parseInt(topKRaw)));
                        } catch (Exception ignored) {
                        }
                    }
                    return searchCodeService.search(query, topK);
                }
        ));
    }

    private void registerWebSearch() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "web_search",
                        "联网搜索最新公开信息，返回标题/链接/摘要",
                        createParameters(
                                new Param("query", "string", "搜索关键词", true),
                                new Param("num", "string", "返回结果数，默认 5", false)
                        )
                ),
                args -> {
                    String query = args.getOrDefault("query", "").trim();
                    if (query.isBlank()) {
                        return error("invalid_query", "query 不能为空");
                    }
                    int num = 5;
                    try {
                        num = Integer.parseInt(args.getOrDefault("num", "5"));
                    } catch (Exception ignored) {
                    }
                    return webSearchService.search(query, num);
                }
        ));
    }

    private void registerWebFetch() {
        registerTool(new RegisteredTool(
                new ToolDefinition(
                        "web_fetch",
                        "抓取网页并提取正文内容",
                        createParameters(new Param("url", "string", "网页 URL", true))
                ),
                args -> {
                    String url = args.getOrDefault("url", "").trim();
                    if (url.isBlank()) {
                        return error("invalid_url", "url 不能为空");
                    }
                    return webFetchService.fetch(url);
                }
        ));
    }

    private String runCommandWithTimeout(String command) {
        Future<CommandResult> future = processExecutor.submit(() -> {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-Command", command)
                    .directory(workspaceRoot.toFile())
                    .start();

            Future<String> stdoutFuture = processExecutor.submit(() -> readProcessStream(process.getInputStream()));
            Future<String> stderrFuture = processExecutor.submit(() -> readProcessStream(process.getErrorStream()));

            boolean done = process.waitFor(COMMAND_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!done) {
                process.destroyForcibly();
                throw new TimeoutException("命令执行超时");
            }

            String stdout = stdoutFuture.get(3, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(3, TimeUnit.SECONDS);
            return new CommandResult(process.exitValue(), stdout, stderr);
        });

        try {
            CommandResult result = future.get(COMMAND_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
            return ok(Map.of(
                    "exitCode", result.exitCode(),
                    "stdout", result.stdout(),
                    "stderr", result.stderr()
            ));
        } catch (TimeoutException e) {
            future.cancel(true);
            return error("command_timeout", "命令执行超时");
        } catch (Exception e) {
            future.cancel(true);
            return error("command_execution_failed", e.getMessage());
        }
    }

    private DSV4Client createInternalLlmClient() {
        String apiKey = firstNonBlank(
                System.getenv("DEEPSEEK_API_KEY"),
                System.getenv("GLM_API_KEY"),
                ""
        );
        String model = firstNonBlank(
                System.getenv("DONGRAN_MODEL"),
                "deepseek-chat"
        );
        String base = firstNonBlank(
                System.getenv("DONGRAN_API_URL"),
                ""
        );
        return new DSV4Client(apiKey, model, base);
    }

    private String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private void loadExternalProviders() {
        ServiceLoader<ToolProvider> loader = ServiceLoader.load(ToolProvider.class);
        for (ToolProvider provider : loader) {
            try {
                List<RegisteredTool> provided = provider.provideTools();
                for (RegisteredTool t : provided) {
                    registerTool(t);
                }
                LOG.info("已加载插件工具数量: {}", provided.size());
            } catch (Exception e) {
                LOG.warn("加载插件失败: {}", provider.getClass().getName(), e);
            }
        }
    }

    private Path safeResolve(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path 不能为空");
        }
        Path resolved = workspaceRoot.resolve(path).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw new IllegalArgumentException("禁止访问工作区外路径");
        }
        return resolved;
    }

    private boolean isAllowedCommand(String command) {
        return COMMAND_ALLOWLIST.stream().anyMatch(command::startsWith);
    }

    private String readProcessStream(java.io.InputStream stream) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                if (lines.size() >= 500) {
                    lines.add("... output truncated ...");
                    break;
                }
            }
        }
        return String.join(System.lineSeparator(), lines);
    }

    private Map<String, String> parseArgs(String argumentsJson) throws Exception {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        JsonNode node = mapper.readTree(argumentsJson);
        Map<String, String> result = new HashMap<>();
        node.fields().forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText("")));
        return result;
    }

    private JsonNode createParameters(Param... params) {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ArrayNode required = root.putArray("required");

        for (Param p : params) {
            ObjectNode prop = props.putObject(p.name());
            prop.put("type", p.type());
            prop.put("description", p.description());
            if (p.required()) {
                required.add(p.name());
            }
        }
        return root;
    }

    private String ok(Map<String, ?> payload) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("ok", true);
            root.set("data", mapper.valueToTree(payload));
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"ok\":true,\"data\":\"serialization_failed\"}";
        }
    }

    private String error(String code, String message) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("ok", false);
            root.put("code", code);
            root.put("message", message == null ? "" : message);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"ok\":false,\"code\":\"unknown\",\"message\":\"serialization_failed\"}";
        }
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}
