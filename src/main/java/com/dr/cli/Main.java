package com.dr.cli;

import com.dr.agent.Agent;
import com.dr.agent.Message;
import com.dr.mcp.McpServerConfig;
import com.dr.mcp.McpServerManager;
import com.dr.tool.ApprovalAwareToolRegistry;
import com.dr.tool.ToolRegistry;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.reader.impl.completer.StringsCompleter;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class Main {
    private static final String VERSION = "1.0.0";
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_BOLD_CYAN = "\u001B[1;36m";
    private static final String ANSI_DIM = "\u001B[2m";
    private static final List<String> COMMAND_SUGGESTIONS = List.of(
            "/help", "/version", "/config", "/history", "/plan", "/team", "/mcp", "/hitl", "/clear", "/exit"
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
            enablePredictiveInput(reader);

            while (true) {
                String input;
                try {
                    input = reader.readLine("you> ");
                } catch (UserInterruptException e) {
                    continue;
                } catch (EndOfFileException e) {
                    break;
                }
                input = input == null ? "" : input.trim();
                if (input.isEmpty()) {
                    continue;
                }

                if (isLocalCommand(input, agent, sessionStore, config, mcpServerManager)) {
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

                String response = agent.run(input);
                System.out.println("assistant> " + response);
                sessionStore.save(agent.snapshotHistory());
            }
        } catch (IOException e) {
            System.err.println("终端补全初始化失败，回退基础输入模式: " + e.getMessage());
            runLoopFallback(agent, sessionStore, config, mcpServerManager);
        }
        mcpServerManager.close();
    }

    private static void runLoopFallback(Agent agent, SessionStore sessionStore, CliConfig config, McpServerManager mcpServerManager) {
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        System.out.println("提示: 当前终端为基础输入模式，预测输入不可用。建议使用系统终端以启用预测输入。");
        while (true) {
            System.out.print("you> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            if (isLocalCommand(input, agent, sessionStore, config, mcpServerManager)) {
                if ("/exit".equalsIgnoreCase(input)) {
                    break;
                }
                continue;
            }
            String response = agent.run(input);
            System.out.println("assistant> " + response);
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

    private static void enablePredictiveInput(LineReader reader) {
        try {
            Class<?> widgetClass = Class.forName("org.jline.widget.AutosuggestionWidgets");
            Object widgets = widgetClass.getConstructor(LineReader.class).newInstance(reader);
            widgetClass.getMethod("enable").invoke(widgets);
        } catch (ClassNotFoundException ex) {
            System.out.println("提示: 当前 JLine 依赖未包含预测输入组件，已使用普通补全。");
        } catch (Exception ex) {
            System.err.println("预测输入初始化失败，已回退为普通补全: " + ex.getMessage());
        }
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

    private static boolean isLocalCommand(String input, Agent agent, SessionStore sessionStore, CliConfig config, McpServerManager mcpServerManager) {
        String normalized = input.toLowerCase();
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
        printHelp();
    }

    private static void printHelp() {
        System.out.println("""
                可用命令:
                  /help     显示帮助
                  /version  显示版本
                  /config   显示当前配置
                  /history  显示当前会话消息数量
                  /plan     显示最近一次 DAG 执行计划与状态
                  /team     多 Agent 模式开关: /team on|off|status|log
                  /mcp      MCP 服务状态: /mcp status
                  /hitl     人工审批开关: /hitl on|off|status|reset
                  /clear    清空会话
                  /exit     退出
                
                启动参数:
                  --version    显示版本后退出
                  --no-banner  不显示启动 Banner
                
                输入增强:
                  - 支持预测输入（基于命令与历史）
                  - 按 Tab 查看补全，按 右箭头 接受预测
                """);
    }

    private record StartupOptions(boolean showVersion, boolean showBanner, boolean enableColor) {
        private static StartupOptions parse(String[] args) {
            boolean showVersion = false;
            boolean showBanner = true;
            for (String arg : args) {
                if ("--version".equalsIgnoreCase(arg)) {
                    showVersion = true;
                } else if ("--no-banner".equalsIgnoreCase(arg)) {
                    showBanner = false;
                }
            }
            return new StartupOptions(showVersion, showBanner, supportsAnsiColor());
        }
    }
}
