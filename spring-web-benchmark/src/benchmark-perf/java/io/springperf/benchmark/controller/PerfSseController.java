
package io.springperf.benchmark.controller;

import io.springperf.web.core.async.stream.SseEmitter;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PerfSseController {

    private static final int SSE_CHUNK_COUNT = 100;
    private static final int SSE_CHUNK_SIZE = 200;
    private static final int SSE_CHUNK_INTERVAL_MS = 0;

    private final TaskExecutor taskExecutor;

    public PerfSseController(TaskExecutor taskExecutor) {
        this.taskExecutor = taskExecutor != null ? taskExecutor : new SimpleAsyncTaskExecutor("sse-");
    }

    @GetMapping("/api/core/sse/stream")
    public SseEmitter sseStream() {
        return createSseEmitter(0);
    }

    private SseEmitter createSseEmitter(int intervalMs) {
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
                    emitter.send(chunkBuf.toString());
                    if (intervalMs > 0) {
                        Thread.sleep(intervalMs);
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}