package io.springperf.benchmark.controller;

import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 端点 — 使用 SseEmitter 返回类型，所有容器走真正的 SSE 流式推送路径。
 * <p>
 * 每个容器使用各自的 SseEmitter 实现：
 * - Tomcat / Undertow: Spring MVC 内置的 SseEmitter
 * - perf-support: spring-web-support 提供的 SseEmitter（继承 StreamEmitter）
 * <p>
 * 数据通过 TaskExecutor 在后台线程生成，由 SseEmitter 异步推送到客户端。
 */
@RestController
public class SseEmitterSseController {

    private static final int SSE_CHUNK_COUNT = 100;
    private static final String SSE_DATA;

    static {
        StringBuilder sb = new StringBuilder(200);
        sb.append("{\"chunk\":0,\"data\":\"");
        while (sb.length() < 198) {
            sb.append("0123456789");
        }
        sb.append("\"}");
        sb.setLength(200);
        SSE_DATA = sb.toString();
    }

    private final TaskExecutor taskExecutor;

    public SseEmitterSseController(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor != null ? taskExecutor : new SimpleAsyncTaskExecutor("sse-");
    }

    @GetMapping("/api/core/sse/stream")
    public SseEmitter sseStream() {
        SseEmitter emitter = new SseEmitter(60000L);
        taskExecutor.execute(() -> {
            try {
                for (int i = 0; i < SSE_CHUNK_COUNT; i++) {
                    emitter.send(SSE_DATA);
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}