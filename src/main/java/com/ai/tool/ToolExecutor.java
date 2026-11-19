package com.ai.tool;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** 工具执行的有界线程池，并把已验证主体传播到工作线程。 */
@Component
public class ToolExecutor {
    private final ThreadPoolExecutor workers;

    public ToolExecutor() {
        this(defaultWorkers());
    }

    ToolExecutor(ThreadPoolExecutor workers) {
        this.workers = workers;
    }

    private static ThreadPoolExecutor defaultWorkers() {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), runnable -> {
                    Thread thread = new Thread(runnable, "ai-tool-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public ToolResult execute(ReadonlyToolRegistry registry, String name, ToolContext context, JsonNode arguments) {
        SecurityContext parent = SecurityContextHolder.getContext();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        FutureTask<ToolResult> task = new FutureTask<>(() -> {
            SecurityContext child = SecurityContextHolder.createEmptyContext();
            child.setAuthentication(parent.getAuthentication());
            SecurityContextHolder.setContext(child);
            if (attributes != null) RequestContextHolder.setRequestAttributes(attributes, true);
            try {
                return registry.execute(name, context, arguments);
            } finally {
                RequestContextHolder.resetRequestAttributes();
                SecurityContextHolder.clearContext();
            }
        });
        try {
            workers.execute(task);
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_TOOL_BUSY");
        }
        try {
            return task.get(ToolLimits.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            task.cancel(true);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AI_TOOL_TIMEOUT");
        } catch (InterruptedException interrupted) {
            task.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AI_TOOL_INTERRUPTED");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof ResponseStatusException status) throw status;
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AI_TOOL_FAILED");
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }
}
