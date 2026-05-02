package com.dr.cli;

import com.dr.agent.Agent;
import com.dr.agent.Message;
import com.dr.mcp.McpServerConfig;
import com.dr.mcp.McpServerManager;
import com.dr.tool.ApprovalAwareToolRegistry;
import com.dr.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static final String VERSION = "1.0.0";
    private static final String BANNER = """
            DDDDDDDDDDDDD                                                                                                                            CCCCCCCCCCCCCLLLLLLLLLLL             IIIIIIIIII
            D::::::::::::DDD                                                                                                                      CCC::::::::::::CL:::::::::L             I::::::::I
            D:::::::::::::::DD                                                                                                                  CC:::::::::::::::CL:::::::::L             I::::::::I
            DDD:::::DDDDD:::::D                                                                                                                C:::::CCCCCCCC::::CLL:::::::LL             II::::::II
              D:::::D    D:::::D    ooooooooooo   nnnn  nnnnnnnn       ggggggggg   gggggrrrrr   rrrrrrrrr   aaaaaaaaaaaaa  nnnn  nnnnnnnn     C:::::C       CCCCCC  L:::::L                 I::::I
              D:::::D     D:::::D oo:::::::::::oo n:::nn::::::::nn    g:::::::::ggg::::gr::::rrr:::::::::r  a::::::::::::a n:::nn::::::::nn  C:::::C                L:::::L                 I::::I
              D:::::D     D:::::Do:::::::::::::::on::::::::::::::nn  g:::::::::::::::::gr:::::::::::::::::r aaaaaaaaa:::::an::::::::::::::nn C:::::C                L:::::L                 I::::I
              D:::::D     D:::::Do:::::ooooo:::::onn:::::::::::::::ng::::::ggggg::::::ggrr::::::rrrrr::::::r         a::::ann:::::::::::::::nC:::::C                L:::::L                 I::::I
              D:::::D     D:::::Do::::o     o::::o  n:::::nnnn:::::ng:::::g     g:::::g  r:::::r     r:::::r  aaaaaaa:::::a  n:::::nnnn:::::nC:::::C                L:::::L                 I::::I
              D:::::D     D:::::Do::::o     o::::o  n::::n    n::::ng:::::g     g:::::g  r:::::r     rrrrrrraa::::::::::::a  n::::n    n::::nC:::::C                L:::::L                 I::::I
              D:::::D     D:::::Do::::o     o::::o  n::::n    n::::ng:::::g     g:::::g  r:::::r           a::::aaaa::::::a  n::::n    n::::nC:::::C                L:::::L                 I::::I
              D:::::D    D:::::D o::::o     o::::o  n::::n    n::::ng::::::g    g:::::g  r:::::r          a::::a    a:::::a  n::::n    n::::n C:::::C       CCCCCC  L:::::L         LLLLLL  I::::I
            DDD:::::DDDDD:::::D  o:::::ooooo:::::o  n::::n    n::::ng:::::::ggggg:::::g  r:::::r          a::::a    a:::::a  n::::n    n::::n  C:::::CCCCCCCC::::CLL:::::::LLLLLLLLL:::::LII::::::II
            D:::::::::::::::DD   o:::::::::::::::o  n::::n    n::::n g::::::::::::::::g  r:::::r          a:::::aaaa::::::a  n::::n    n::::n   CC:::::::::::::::CL::::::::::::::::::::::LI::::::::I
            D::::::::::::DDD      oo:::::::::::oo   n::::n    n::::n  gg::::::::::::::g  r:::::r           a::::::::::aa:::a n::::n    n::::n     CCC::::::::::::CL::::::::::::::::::::::LI::::::::I
            DDDDDDDDDDDDD           ooooooooooo     nnnnnn    nnnnnn    gggggggg::::::g  rrrrrrr            aaaaaaaaaa  aaaa nnnnnn    nnnnnn        CCCCCCCCCCCCCLLLLLLLLLLLLLLLLLLLLLLLLIIIIIIIIII
                                                                                g:::::g
                                                                    gggggg      g:::::g
                                                                    g:::::gg   gg:::::g
                                                                     g::::::ggg:::::::g
                                                                      gg:::::::::::::g
                                                                        ggg::::::ggg
                                                                           gggggg
            """;

    public static void main(String[] args) {
        if (args.length > 0 && "--version".equalsIgnoreCase(args[0])) {
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
        List<McpServerConfig> mcpConfigs = McpServerManager.parseFromEnv();
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

        printBanner();
        printWelcome();
        runLoop(agent, sessionStore, config, mcpServerManager);
    }

    private static void printBanner() {
        System.out.println(BANNER);
    }

    private static void runLoop(Agent agent, SessionStore sessionStore, CliConfig config, McpServerManager mcpServerManager) {
        Scanner scanner = new Scanner(System.in);
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
        mcpServerManager.close();
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
                """);
    }
}
