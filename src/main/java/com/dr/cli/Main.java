package com.dr.cli;

import com.dr.agent.Agent;
import com.dr.agent.Message;
import com.dr.mcp.McpServerConfig;
import com.dr.mcp.McpServerManager;
import com.dr.tool.ApprovalAwareToolRegistry;
import com.dr.tool.ToolRegistry;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {
    private static final String VERSION = "1.0.0";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD_CYAN = "\u001B[1;36m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final String ANSI_BOLD = "\u001B[1m";
    private static final String ANSI_CYAN = "\u001B[36m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static volatile boolean UNICODE_UI = true;
    private static volatile boolean COLOR_UI = true;
    private static final List<String> COMMAND_SUGGESTIONS = List.of(
            "/help", "/version", "/config", "/history", "/plan", "/team", "/mcp", "/hitl", "/security", "/image", "/clear", "/exit"
    );
    private static final String BANNER = """
           
            ░███████              ░██████  ░██         ░██████
            ░██   ░██            ░██   ░██ ░██           ░██ \s
            ░██    ░██ ░██░████ ░██        ░██           ░██ \s
            ░██    ░██ ░███     ░██        ░██           ░██ \s
            ░██    ░██ ░██      ░██        ░██           ░██ \s
            ░██   ░██  ░██       ░██   ░██ ░██           ░██ \s
            ░███████   ░██        ░██████  ░██████████ ░██████
                                                             \s
                                                             \s
                                                             \s                                                                                                                 \s
                                                                                                                                        \s
                     Dongran CLI
            """;

    public static void main(String[] args) {
        StartupOptions options = StartupOptions.parse(args);
        UNICODE_UI = options.unicodeUi();
        COLOR_UI = options.enableColor();
        if (options.showVersion()) {
            System.out.println("Dongran CLI " + VERSION);
            return;
        }

        Path workspace = Path.of(".").toAbsolutePath().normalize();
        CliConfig config = CliConfig.load(workspace);
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            System.err.println("错误: 未找到 API Key。请设置 DEEPSEEK_API_KEY（兼容 GLM_API_KEY）或在 .env / config.properties 中配置 api.key");
            return;
        }

        ApprovalAwareToolRegistry toolRegistry = ApprovalAwareToolRegistry.createDefault(workspace);
        McpServerManager mcpServerManager = new McpServerManager(workspace);
        List<McpServerConfig> mcpConfigs = McpServerManager.parseFromEnv(workspace);
        if (!mcpConfigs.isEmpty()) {
            mcpServerManager.startAll(mcpConfigs, toolRegistry);
            System.out.println("[MCP] 已加载: " + mcpServerManager.status());
        }
        Agent agent = new Agent(config.apiKey(), config.model(), config.apiUrl(), toolRegistry, workspace);
        SessionStore sessionStore = new SessionStore(workspace);

        List<Message> existing = sessionStore.load();
        if (!existing.isEmpty()) {
            agent.restoreHistory(existing);
            System.out.println("[Session] 已恢复上次会话。输入 /clear 清空。");
        }

        printBanner(options);
        printWelcome();
        runLoop(agent, sessionStore, config, mcpServerManager);
    }

    private static void printBanner(StartupOptions options) {
        if (!options.showBanner()) {
            return;
        }
        if (options.enableColor()) {
            System.out.println(ANSI_BOLD_CYAN + BANNER + ANSI_RESET);
            System.out.println(ANSI_DIM + "AI coding assistant in your terminal" + ANSI_RESET);
            return;
        }
        System.out.println(BANNER);
        System.out.println("AI coding assistant in your terminal");
    }

    private static boolean supportsAnsiColor() {
        String noColor = System.getenv("NO_COLOR");
        if (noColor != null && !noColor.isBlank()) {
            return false;
        }
        String term = System.getenv("TERM");
        if (term != null && term.toLowerCase(Locale.ROOT).contains("dumb")) {
            return false;
        }
        return System.console() != null;
    }

    private static void runLoop(Agent agent, SessionStore sessionStore, CliConfig config, McpServerManager mcpServerManager) {
        try {
            Terminal terminal = TerminalBuilder.builder().system(true).build();
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new StringsCompleter(COMMAND_SUGGESTIONS))
                    .build();
            enablePredictiveInput(reader, agent.snapshotHistory());
            AttachmentState attachmentState = new AttachmentState();
            bindImageShortcut(reader, attachmentState);

            while (true) {
                String input;
                try {
                    printComposer(reader, agent, attachmentState);
                    input = reader.readLine(userPrompt());
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                input = input == null ? "" : input.trim();
                if (input.isEmpty()) {
                    continue;
                }
                if (tryAttachPastedImagePath(input, attachmentState, reader)) {
                    continue;
                }
                if (isLocalCommand(input, agent, sessionStore, config, mcpServerManager, attachmentState)) {
                    if ("/exit".equalsIgnoreCase(input)) {
                        break;
                    }
                    continue;
                }
                if (input.startsWith("/") && !input.contains(" ")) {
                    String suggestion = suggestCommand(input);
                    if (suggestion != null) {
                        System.out.println("提示: 未知命令，是否想输入 " + suggestion + " ?");
                        continue;
                    }
                }
                String effectiveInput = mergeAttachment(input, attachmentState);
                AtomicBoolean streamStarted = new AtomicBoolean(false);
                String response = agent.run(
                        effectiveInput,
                        Main::printThinkingProgress,
                        chunk -> printAssistantChunk(chunk, streamStarted)
                );
                if (streamStarted.get()) {
                    System.out.println();
                } else {
                    System.out.println(assistantPrefix() + response);
                }
                printTokenUsage(agent);
                sessionStore.save(agent.snapshotHistory());
            }
        } catch (IOException e) {
            System.err.println("终端补全初始化失败，回退基础输入模式: " + e.getMessage());
            runLoopFallback(agent, sessionStore, config, mcpServerManager);
        } finally {
            mcpServerManager.close();
        }
    }

    private static void runLoopFallback(Agent agent, SessionStore sessionStore, CliConfig config, McpServerManager mcpServerManager) {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        System.out.println("提示: 当前终端为基础输入模式，预测输入不可用。建议使用系统终端以启用预测输入。");
        AttachmentState attachmentState = new AttachmentState();
        while (true) {
            printComposer(agent, attachmentState);
            System.out.print(userPrompt());
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            if (tryAttachPastedImagePath(input, attachmentState, null)) {
                continue;
            }
            if (isLocalCommand(input, agent, sessionStore, config, mcpServerManager, attachmentState)) {
                if ("/exit".equalsIgnoreCase(input)) {
                    break;
                }
                continue;
            }
            String effectiveInput = mergeAttachment(input, attachmentState);
            AtomicBoolean streamStarted = new AtomicBoolean(false);
            String response = agent.run(
                    effectiveInput,
                    Main::printThinkingProgress,
                    chunk -> printAssistantChunk(chunk, streamStarted)
            );
            if (streamStarted.get()) {
                System.out.println();
            } else {
                System.out.println(assistantPrefix() + response);
            }
            printTokenUsage(agent);
            sessionStore.save(agent.snapshotHistory());
        }
    }


    private static String suggestCommand(String input) {
        String in = input.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String cmd : COMMAND_SUGGESTIONS) {
            int d = levenshtein(in, cmd.toLowerCase(Locale.ROOT));
            if (d < bestDistance) {
                bestDistance = d;
                best = cmd;
            }
        }
        return bestDistance <= 3 ? best : null;
    }

    private static void enablePredictiveInput(LineReader reader, List<Message> historyMessages) {
        String[] candidates = new String[] {
                "org.jline.widget.AutosuggestionWidgets",
                "org.jline.builtins.Widgets$AutosuggestionWidgets"
        };
        try {
            for (String clazz : candidates) {
                try {
                    Class<?> widgetClass = Class.forName(clazz);
                    Object widgets = widgetClass.getConstructor(LineReader.class).newInstance(reader);
                    widgetClass.getMethod("enable").invoke(widgets);
                    seedPredictiveHistory(reader, historyMessages);
                    System.out.println(renderUiLine("✨ 输入预测已启用（右箭头接受建议，Tab 查看补全）"));
                    return;
                } catch (ClassNotFoundException ignored) {
                    // try next candidate
                }
            }
            System.out.println("提示: 当前运行包未包含预测输入组件，已使用普通补全。请执行 mvn clean package 后使用最新 jar 运行。");
        } catch (Exception ex) {
            System.err.println("预测输入初始化失败，已回退为普通补全: " + ex.getMessage());
        }
    }

    private static void bindImageShortcut(LineReader reader, AttachmentState attachmentState) {
        try {
            reader.getWidgets().put("attach-image", () -> {
                String attached = attachFromClipboard(attachmentState);
                if (attached == null) {
                    reader.printAbove("未检测到可用图片。先复制图片文件路径，或在资源管理器中复制图片文件后再按 Alt+V。");
                } else {
                    reader.printAbove(renderComposerLines(null, attachmentState));
                }
                return true;
            });
            KeyMap<org.jline.reader.Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
            if (main != null) {
                main.bind(new Reference("attach-image"), "^V");
            }
        } catch (Exception ignored) {
            // 绑定失败时不影响主流程
        }
    }

    private static void seedPredictiveHistory(LineReader reader, List<Message> historyMessages) {
        try {
            LinkedHashSet<String> seeds = new LinkedHashSet<>();
            for (String cmd : COMMAND_SUGGESTIONS) {
                seeds.add(cmd);
            }
            if (historyMessages != null) {
                for (Message m : historyMessages) {
                    if (m == null || !"user".equalsIgnoreCase(m.getRole())) {
                        continue;
                    }
                    String content = m.getContent() == null ? "" : m.getContent().trim();
                    if (content.isBlank() || content.length() < 2 || content.length() > 220) {
                        continue;
                    }
                    seeds.add(content);
                }
            }
            for (String seed : seeds) {
                reader.getHistory().add(seed);
            }
        } catch (Exception ignore) {
            // 历史注入失败时不影响主流程
        }
    }

    private static void printThinkingProgress(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        String rendered = renderUiLine(line);
        if (line.startsWith("🧠") || line.startsWith("🛠️") || line.startsWith("🗺️") || line.startsWith("⚙️")
                || line.startsWith("👥") || line.startsWith("⚠️") || line.startsWith("✅")) {
            System.out.println(rendered);
            return;
        }
        System.out.println(thinkingPrefix() + rendered);
    }

    private static void printAssistantChunk(String chunk, AtomicBoolean started) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        if (!started.get()) {
            System.out.print(assistantPrefix());
            started.set(true);
        }
        System.out.print(chunk);
        System.out.flush();
    }

    private static void printTokenUsage(Agent agent) {
        if (agent == null) {
            return;
        }
        String line = agent.lastTokenUsageLine();
        if (line == null || line.isBlank()) {
            return;
        }
        System.out.println(ANSI_DIM + renderUiLine(line) + ANSI_RESET);
    }

    private static String userPrompt() {
        if (!UNICODE_UI) {
            return "you> ";
        }
        if (COLOR_UI) {
            return ANSI_BOLD_CYAN + "❯ " + ANSI_RESET;
        }
        return "> ";
    }

    private static String assistantPrefix() {
        return UNICODE_UI ? "🤖 回答> " : "assistant> ";
    }

    private static String thinkingPrefix() {
        return UNICODE_UI ? "🧠 " : "[思考] ";
    }

    private static String renderUiLine(String line) {
        if (UNICODE_UI || line == null || line.isBlank()) {
            return line;
        }
        return line
                .replace("🧠", "[思考]")
                .replace("🛠️", "[工具]")
                .replace("🛠", "[工具]")
                .replace("🗺️", "[规划]")
                .replace("🗺", "[规划]")
                .replace("⚙️", "[执行]")
                .replace("⚙", "[执行]")
                .replace("👥", "[协作]")
                .replace("⚠️", "[警告]")
                .replace("⚠", "[警告]")
                .replace("✅", "[完成]")
                .replace("✨", "[提示]")
                .replace("🔢", "[Token]");
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[a.length()][b.length()];
    }

    private static boolean isLocalCommand(
            String input,
            Agent agent,
            SessionStore sessionStore,
            CliConfig config,
            McpServerManager mcpServerManager,
            AttachmentState attachmentState
    ) {
        String normalized = input.toLowerCase();
        if (normalized.startsWith("/image")) {
            String path = input.length() > 6 ? input.substring(6).trim() : "";
            if (path.isBlank()) {
                System.out.println("用法: /image <path> | /image paste  （可按 Ctrl+V 快速附加）");
                return true;
            }
            if ("paste".equalsIgnoreCase(path)) {
                String attached = attachFromClipboard(attachmentState);
                if (attached == null) {
                    System.out.println("未检测到可用图片。先复制图片文件，或复制图片路径后再执行 /image paste。");
                    return true;
                }
                System.out.println(renderAttachmentSummary(attachmentState));
                return true;
            }
            if ("clear".equalsIgnoreCase(path)) {
                attachmentState.clear();
                System.out.println("已清空待发送图片附件。");
                return true;
            }
            String attached = buildImageAttachment(path);
            if (attached == null) {
                System.out.println("图片读取失败: 文件不存在、无法访问或格式不支持（仅支持 png/jpg/jpeg/gif/webp）");
                return true;
            }
            attachmentState.add(attached);
            System.out.println(renderAttachmentSummary(attachmentState));
            return true;
        }
        if (normalized.startsWith("/mcp")) {
            String[] parts = input.trim().split("\\s+");
            if (parts.length == 1 || "status".equalsIgnoreCase(parts[1])) {
                System.out.println(mcpServerManager.status());
            } else {
                System.out.println("用法: /mcp [status]");
            }
            return true;
        }
        if (normalized.startsWith("/hitl")) {
            String[] parts = input.trim().split("\\s+");
            ToolRegistry raw = agent.getToolRegistry();
            if (!(raw instanceof ApprovalAwareToolRegistry ar)) {
                System.out.println("当前 Registry 不支持 HITL");
                return true;
            }
            if (parts.length == 1 || "status".equalsIgnoreCase(parts[1])) {
                System.out.println("HITL: " + (ar.isHitlEnabled() ? "ON" : "OFF"));
            } else if ("on".equalsIgnoreCase(parts[1])) {
                ar.setHitlEnabled(true);
                System.out.println("HITL 已开启");
            } else if ("off".equalsIgnoreCase(parts[1])) {
                ar.setHitlEnabled(false);
                System.out.println("HITL 已关闭");
            } else if ("reset".equalsIgnoreCase(parts[1])) {
                ar.resetSessionApprovals();
                System.out.println("HITL 会话缓存已清空");
            } else {
                System.out.println("用法: /hitl [on|off|status|reset]");
            }
            return true;
        }
        if (normalized.startsWith("/team")) {
            String[] parts = input.trim().split("\\s+");
            if (parts.length == 1 || "status".equalsIgnoreCase(parts[1])) {
                System.out.println("Team 模式: " + (agent.isTeamModeEnabled() ? "ON" : "OFF"));
            } else if ("on".equalsIgnoreCase(parts[1])) {
                agent.setTeamModeEnabled(true);
                System.out.println("Team 模式已开启（Planner/Worker/Reviewer）");
            } else if ("off".equalsIgnoreCase(parts[1])) {
                agent.setTeamModeEnabled(false);
                System.out.println("Team 模式已关闭");
            } else if ("log".equalsIgnoreCase(parts[1])) {
                System.out.println(agent.latestTeamSummary());
            } else {
                System.out.println("用法: /team [on|off|status|log]");
            }
            return true;
        }
        if (normalized.startsWith("/security")) {
            String[] parts = input.trim().split("\\s+");
            ToolRegistry registry = agent.getToolRegistry();
            if (parts.length == 1 || "status".equalsIgnoreCase(parts[1])) {
                System.out.println("Security Mode: " + registry.getSecurityMode().name().toLowerCase(Locale.ROOT));
            } else {
                ToolRegistry.SecurityMode mode = ToolRegistry.SecurityMode.from(parts[1], null);
                if (mode == null) {
                    System.out.println("用法: /security [strict|balanced|permissive|status]");
                } else {
                    registry.setSecurityMode(mode);
                    System.out.println("Security Mode 已切换为: " + mode.name().toLowerCase(Locale.ROOT));
                }
            }
            return true;
        }

        switch (normalized) {
            case "/help" -> {
                printHelp();
                return true;
            }
            case "/version" -> {
                System.out.println("Dongran CLI " + VERSION);
                return true;
            }
            case "/config" -> {
                System.out.println(config);
                return true;
            }
            case "/history" -> {
                List<Message> history = agent.snapshotHistory();
                System.out.println("历史消息数: " + history.size());
                return true;
            }
            case "/plan" -> {
                System.out.println(agent.latestPlanSummary());
                return true;
            }
            case "/clear" -> {
                agent.clearHistory();
                sessionStore.clear();
                System.out.println("会话历史已清空");
                return true;
            }
            case "/exit" -> {
                sessionStore.save(agent.snapshotHistory());
                System.out.println("Bye.");
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void printWelcome() {
        System.out.println("Dongran CLI 已启动");
        System.out.println(helpText());
    }

    private static void printHelp() {
        System.out.println(helpText());
    }

    private static String helpText() {
        return """
                可用命令:
                  /help     显示帮助
                  /version  显示版本
                  /config   显示当前配置
                  /history  显示当前会话消息数量
                  /plan     显示最近一次 DAG 执行计划与状态
                  /team     多 Agent 模式开关: /team on|off|status|log
                  /mcp      MCP 服务状态: /mcp status
                  /hitl     人工审批开关: /hitl on|off|status|reset
                  /security 安全模式: /security strict|balanced|permissive|status
                  /image    添加图片上下文: /image <path> | /image paste | /image clear（Ctrl+V 快捷键）
                  /clear    清空会话
                  /exit     退出
                
                启动参数:
                  --version    显示版本后退出
                  --no-banner  不显示启动 Banner
                  --plain-ui   使用纯文本 UI（无 emoji 图标）
                  --unicode-ui 强制启用 Unicode UI（含 emoji 图标）
                
                输入增强:
                  - 支持预测输入（基于命令与历史）
                  - 按 Tab 查看补全，按 右箭头 接受预测
                  - 按 Ctrl+V 快速附加图片
                """;
    }

    private static boolean supportsUnicodeUi() {
        String plainEnv = System.getenv("DONGRAN_PLAIN_UI");
        if (plainEnv != null && "true".equalsIgnoreCase(plainEnv.trim())) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Windows 终端字体和编码环境差异大，默认降级为纯文本，避免图标乱码。
        return !os.contains("windows");
    }

    private record StartupOptions(boolean showVersion, boolean showBanner, boolean enableColor, boolean unicodeUi) {
        private static StartupOptions parse(String[] args) {
            boolean showVersion = false;
            boolean showBanner = true;
            boolean unicodeUi = supportsUnicodeUi();
            for (String arg : args) {
                if ("--version".equalsIgnoreCase(arg)) {
                    showVersion = true;
                } else if ("--no-banner".equalsIgnoreCase(arg)) {
                    showBanner = false;
                } else if ("--plain-ui".equalsIgnoreCase(arg)) {
                    unicodeUi = false;
                } else if ("--unicode-ui".equalsIgnoreCase(arg)) {
                    unicodeUi = true;
                }
            }
            return new StartupOptions(showVersion, showBanner, supportsAnsiColor(), unicodeUi);
        }
    }

    private static String mergeAttachment(String input, AttachmentState attachmentState) {
        if (attachmentState == null || attachmentState.pendingImageNotes.isEmpty()) {
            return input;
        }
        String images = String.join(System.lineSeparator(), attachmentState.pendingImageNotes);
        String merged = """
                [图片上下文]
                %s

                [用户问题]
                %s
                """.formatted(images, input);
        attachmentState.clear();
        return merged;
    }

    private static String buildImageAttachment(String rawPath) {
        try {
            String p = normalizeImagePath(rawPath);
            if (p.isBlank()) {
                return null;
            }
            Path path = resolveImagePath(p);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                return null;
            }
            String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean okExt = lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                    || lower.endsWith(".gif") || lower.endsWith(".webp");
            if (!okExt) {
                return null;
            }
            long size = Files.size(path);
            String digest = sha256(path);
            return "path=" + path + ", size=" + size + " bytes, sha256=" + digest;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeImagePath(String raw) {
        String p = raw == null ? "" : raw.trim();
        p = p.replace('\uFEFF', ' ')
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\u2060", "")
                .trim();
        p = Normalizer.normalize(p, Normalizer.Form.NFKC);
        if ((p.startsWith("\"") && p.endsWith("\""))
                || (p.startsWith("'") && p.endsWith("'"))
                || (p.startsWith("“") && p.endsWith("”"))
                || (p.startsWith("‘") && p.endsWith("’"))) {
            p = p.substring(1, p.length() - 1).trim();
        }
        if (p.endsWith(",")) {
            p = p.substring(0, p.length() - 1).trim();
        }
        if (p.startsWith("file:/")) {
            try {
                p = Path.of(URI.create(p)).toString();
            } catch (Exception ignored) {
                // keep original when uri parsing fails
            }
        }
        return p;
    }

    private static Path resolveImagePath(String pathText) {
        String p = normalizeImagePath(pathText);
        File file = new File(p);
        if (file.exists()) {
            return file.toPath().toAbsolutePath().normalize();
        }
        Path path = Path.of(p);
        return path.toAbsolutePath().normalize();
    }

    private static String sha256(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            return "n/a";
        }
    }

    private static String readClipboardText() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            return data == null ? "" : String.valueOf(data);
        } catch (Exception e) {
            return "";
        }
    }

    private static String attachFromClipboard(AttachmentState attachmentState) {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.javaFileListFlavor);
            if (data instanceof Collection<?> files) {
                for (Object file : files) {
                    String attached = buildImageAttachment(String.valueOf(file));
                    if (attached != null) {
                        attachmentState.add(attached);
                        return attached;
                    }
                }
            }
        } catch (Exception ignored) {
            // fall back to string clipboard
        }
        String clip = readClipboardText();
        if (clip == null || clip.isBlank()) {
            return null;
        }
        for (String candidate : clip.split("\\R")) {
            String attached = buildImageAttachment(candidate);
            if (attached != null) {
                attachmentState.add(attached);
                return attached;
            }
        }
        return null;
    }

    private static boolean tryAttachPastedImagePath(String input, AttachmentState attachmentState, LineReader reader) {
        if (input == null || input.isBlank() || input.startsWith("/")) {
            return false;
        }
        String attached = buildImageAttachment(input);
        if (attached == null) {
            return false;
        }
        attachmentState.add(attached);
        String summary = renderComposerLines(null, attachmentState);
        if (reader != null) {
            reader.printAbove(summary);
        } else {
            System.out.println(summary);
        }
        return true;
    }

    private static void printComposer(LineReader reader, Agent agent, AttachmentState attachmentState) {
        if (reader == null) {
            return;
        }
        reader.printAbove(renderComposerLines(agent, attachmentState));
    }

    private static void printComposer(Agent agent, AttachmentState attachmentState) {
        System.out.println(renderComposerLines(agent, attachmentState));
    }

    private static String renderAttachmentSummary(AttachmentState attachmentState) {
        if (UNICODE_UI) {
            return "📎 " + attachmentState.chips();
        }
        return "[附件] " + attachmentState.chips();
    }

    private static String renderComposerLines(Agent agent, AttachmentState attachmentState) {
        String lineSeparator = System.lineSeparator();
        StringBuilder sb = new StringBuilder();
        sb.append(renderComposerHeaderTop(agent))
                .append(lineSeparator)
                .append(renderComposerHeaderBottom(agent));
        if (attachmentState != null && !attachmentState.pendingImageNotes.isEmpty()) {
            sb.append(lineSeparator).append(renderAttachmentSummary(attachmentState));
        }
        return sb.toString();
    }

    private static String renderComposerHeaderTop(Agent agent) {
        if (!UNICODE_UI) {
            return "[Dongran]";
        }
        if (COLOR_UI) {
            return ANSI_DIM + "╭─ " + ANSI_RESET + ANSI_BOLD + "Dongran" + ANSI_RESET;
        }
        return "Dongran";
    }

    private static String renderComposerHeaderBottom(Agent agent) {
        String security = agent == null ? "balanced"
                : agent.getToolRegistry().getSecurityMode().name().toLowerCase(Locale.ROOT);
        String hitl = "n/a";
        if (agent != null && agent.getToolRegistry() instanceof ApprovalAwareToolRegistry ar) {
            hitl = ar.isHitlEnabled() ? "on" : "off";
        }
        int historyCount = agent == null ? 0 : agent.snapshotHistory().size();
        if (!UNICODE_UI) {
            return " model=" + safeModel(agent)
                    + " | security=" + security
                    + " | hitl=" + hitl
                    + " | history=" + historyCount;
        }
        if (COLOR_UI) {
            return ANSI_DIM + "│  " + ANSI_RESET
                    + ANSI_DIM + "model " + ANSI_RESET + ANSI_CYAN + safeModel(agent) + ANSI_RESET
                    + ANSI_DIM + "  security " + ANSI_RESET + ANSI_YELLOW + security + ANSI_RESET
                    + ANSI_DIM + "  hitl " + ANSI_RESET + hitl
                    + ANSI_DIM + "  history " + ANSI_RESET + historyCount;
        }
        return " model " + safeModel(agent)
                + "  security " + security
                + "  hitl " + hitl
                + "  history " + historyCount;
    }

    private static String safeModel(Agent agent) {
        try {
            if (agent == null || agent.getConfigModel() == null || agent.getConfigModel().isBlank()) {
                return "default";
            }
            return agent.getConfigModel();
        } catch (Exception e) {
            return "default";
        }
    }

    private static class AttachmentState {
        private final List<String> pendingImageNotes = new ArrayList<>();

        private void add(String note) {
            if (note != null && !note.isBlank()) {
                pendingImageNotes.add(note);
            }
        }

        private void clear() {
            pendingImageNotes.clear();
        }

        private String chips() {
            List<String> names = new ArrayList<>();
            for (String note : pendingImageNotes) {
                names.add(extractFileName(note));
            }
            return String.join("  ", names.stream().map(this::chipOf).toList());
        }

        private String chipOf(String fileName) {
            if (fileName == null || fileName.isBlank()) {
                fileName = "image";
            }
            if (UNICODE_UI) {
                return "▣ " + fileName;
            }
            return "[" + fileName + "]";
        }

        private String extractFileName(String note) {
            int start = note.indexOf("path=");
            int end = note.indexOf(",", start);
            if (start < 0) {
                return "image";
            }
            String path = end > start ? note.substring(start + 5, end) : note.substring(start + 5);
            try {
                return Path.of(path).getFileName().toString();
            } catch (Exception e) {
                return path;
            }
        }
    }

}
