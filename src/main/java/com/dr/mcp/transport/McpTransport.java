package com.dr.mcp.transport;

import java.util.function.Consumer;

public interface McpTransport extends AutoCloseable {
    void connect(Consumer<String> inboundConsumer) throws Exception;

    void send(String message) throws Exception;

    String name();

    @Override
    void close() throws Exception;
}
