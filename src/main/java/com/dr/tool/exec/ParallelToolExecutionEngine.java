package com.dr.tool.exec;

import com.dr.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class ParallelToolExecutionEngine {
    private static final Logger LOG = LoggerFactory.getLogger(ParallelToolExecutionEngine.class);
    private static final int MAX_PARALLEL = 4;
    private static final Duration DEFAULT_BATCH_TIMEOUT = Duration.ofSeconds(30);

    private final ToolRegistry toolRegistry;
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL);

    public ParallelToolExecutionEngine(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    public List<ToolExecutionResult> executeBatch(List<ToolInvocation> invocations, Duration timeout) {
        Duration effectiveTimeout = timeout == null || timeout.isNegative() || timeout.isZero()
                ? DEFAULT_BATCH_TIMEOUT
                : timeout;
        long deadlineNanos = System.nanoTime() + effectiveTimeout.toNanos();

        List<Future<ToolExecutionResult>> futures = new ArrayList<>();
        for (ToolInvocation inv : invocations) {
            futures.add(executor.submit(() -> runSingle(inv)));
        }

        List<ToolExecutionResult> out = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            ToolInvocation inv = invocations.get(i);
            Future<ToolExecutionResult> future = futures.get(i);
            long remain = deadlineNanos - System.nanoTime();
            if (remain <= 0) {
                future.cancel(true);
                out.add(new ToolExecutionResult(
                        inv.toolCallId(), inv.toolName(), ToolExecutionStatus.TIMEOUT,
                        null, "批次超时，工具被取消", effectiveTimeout.toMillis()
                ));
                continue;
            }
            try {
                out.add(future.get(remain, TimeUnit.NANOSECONDS));
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                out.add(new ToolExecutionResult(
                        inv.toolCallId(), inv.toolName(), ToolExecutionStatus.TIMEOUT,
                        null, "工具执行超时并已取消", effectiveTimeout.toMillis()
                ));
            } catch (java.util.concurrent.CancellationException e) {
                out.add(new ToolExecutionResult(
                        inv.toolCallId(), inv.toolName(), ToolExecutionStatus.CANCELLED,
                        null, "工具被取消", 0
                ));
            } catch (Exception e) {
                LOG.debug("并行工具执行失败: {}", inv.toolName(), e);
                out.add(new ToolExecutionResult(
                        inv.toolCallId(), inv.toolName(), ToolExecutionStatus.FAILED,
                        null, e.getMessage(), 0
                ));
            }
        }
        return out;
    }

    private ToolExecutionResult runSingle(ToolInvocation inv) {
        long started = System.currentTimeMillis();
        try {
            String result = toolRegistry.executeTool(inv.toolName(), inv.argumentsJson());
            return new ToolExecutionResult(
                    inv.toolCallId(),
                    inv.toolName(),
                    ToolExecutionStatus.SUCCESS,
                    result,
                    null,
                    System.currentTimeMillis() - started
            );
        } catch (Exception e) {
            return new ToolExecutionResult(
                    inv.toolCallId(),
                    inv.toolName(),
                    ToolExecutionStatus.FAILED,
                    null,
                    e.getMessage(),
                    System.currentTimeMillis() - started
            );
        }
    }
}
