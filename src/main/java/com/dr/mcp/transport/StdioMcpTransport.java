package com.dr.mcp.transport;

import com.dr.mcp.McpServerConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class StdioMcpTransport implements McpTransport {
    private final McpServerConfig config;
    private final ExecutorService readExecutor = Executors.newSingleThreadExecutor();
    private Process process;
    private BufferedWriter writer;

    public StdioMcpTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void connect(Consumer<String> inboundConsumer) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.command());
        if (config.args() != null) {
            cmd.addAll(config.args());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (config.env() != null) {
            pb.environment().putAll(config.env());
        }
        process = pb.start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        readExecutor.submit(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        inboundConsumer.accept(line);
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void send(String message) throws Exception {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    @Override
    public String name() {
        return "stdio";
    }

    @Override
    public void close() throws Exception {
        if (writer != null) {
            writer.close();
        }
        if (process != null) {
            process.destroyForcibly();
        }
        readExecutor.shutdownNow();
    }
}
