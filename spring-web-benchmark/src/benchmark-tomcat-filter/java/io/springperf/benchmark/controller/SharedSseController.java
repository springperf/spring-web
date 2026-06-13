package io.springperf.benchmark.controller;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 端点 — 适用于 perf-support-filter、Tomcat、Undertow 等 servlet 容器。
 * <p>
 * 使用 TaskExecutor 线程池执行异步 SSE 推送，避免直接创建线程。
 * perf-support 通过 spring-web-support 提供的同名 SseEmitter（继承 perf 的 StreamEmitter）
 * 与 Spring MVC 共享同一份字节码，在各自框架的异步处理管线中正常工作。
 */
@RestController
public class SharedSseController {

    private static final int SSE_CHUNK_COUNT = 100;
    private static final int SSE_CHUNK_SIZE = 200;
    private static final int SSE_CHUNK_INTERVAL_MS = 0;

    /**
     * Spring Boot 自动配置的 TaskExecutor（不可用时回退到 SimpleAsyncTaskExecutor）。
     * <p>
     * 使用 setter 注入而非构造器注入，避免构造器参数解析可能导致的循环依赖
     * 或 TaskExecutor bean 不存在时的启动失败。
     */
    private final TaskExecutor taskExecutor;

    public SharedSseController(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor != null ? taskExecutor : new SimpleAsyncTaskExecutor("sse-");
    }

    @GetMapping("/api/core/sse/stream")
    public SseEmitter sseStream() {
        SseEmitter emitter = new SseEmitter(60000L);
        taskExecutor.execute(() -> {
            try {
                StringBuilder chunkBuf = new StringBuilder(SSE_CHUNK_SIZE);
                for (int i = 0; i < SSE_CHUNK_COUNT; i++) {
                    chunkBuf.setLength(0);
                    chunkBuf.append("{\"chunk\":").append(i).append(",\"data\":\"");
                    while (chunkBuf.length() < SSE_CHUNK_SIZE - 2) {
                        chunkBuf.append("0123456789");
                    }
                    chunkBuf.append("\"}");
                    chunkBuf.setLength(SSE_CHUNK_SIZE);
                    emitter.send(SseEmitter.event().data(chunkBuf.toString()));
                    Thread.sleep(SSE_CHUNK_INTERVAL_MS);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}