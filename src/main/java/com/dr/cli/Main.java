package com.dr.cli;

import com.dr.agent.Agent;
import com.dr.agent.Message;
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

        ToolRegistry toolRegistry = new ToolRegistry(workspace);
        Agent agent = new Agent(config.apiKey(), config.model(), config.apiUrl(), toolRegistry);
        SessionStore sessionStore = new SessionStore(workspace);

        List<Message> existing = sessionStore.load();
        if (!existing.isEmpty()) {
            agent.restoreHistory(existing);
            System.out.println("[Session] 已恢复上次会话。输入 /clear 清空。");
        }

        printBanner();
        printWelcome();
        runLoop(agent, sessionStore, config);
    }

    private static void printBanner() {
        System.out.println(BANNER);
    }

    private static void runLoop(Agent agent, SessionStore sessionStore, CliConfig config) {
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

            if (isLocalCommand(input, agent, sessionStore, config)) {
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

    private static boolean isLocalCommand(String input, Agent agent, SessionStore sessionStore, CliConfig config) {
        switch (input.toLowerCase()) {
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
                  /clear    清空会话
                  /exit     退出
                """);
    }
}
