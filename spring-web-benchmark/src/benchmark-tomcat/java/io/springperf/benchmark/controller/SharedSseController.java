package io.springperf.benchmark.controller;

import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;

/**
 * SSE 端点 — 适用于 perf-support-filter、Tomcat、Undertow 等 servlet 容器。
 * <p>
 * 同步写入 SSE 数据到 OutputStream，在请求处理线程（容器 worker 线程）上执行。
 * 不使用 SseEmitter + TaskExecutor，避免 Undertow 中 {@code NioSocketConduit}
 * 在非 XNIO 线程上执行 blocking write 时因 TCP 缓冲满而永久阻塞的问题。
 * <p>
 * perf-support 通过 spring-web-support 提供的同名 SseEmitter（继承 perf 的 StreamEmitter）
 * 与 Spring MVC 共享同一份字节码，在各自框架的异步处理管线中正常工作。
 */
@RestController
public class SharedSseController {

    private static final int SSE_CHUNK_COUNT = 100;
    private static final int SSE_CHUNK_SIZE = 200;

    @GetMapping("/api/core/sse/stream")
    public void sseStream(HttpServletResponse response) throws Exception {
        response.setContentType("text/event-stream");
        response.setHeader("Cache-Control", "no-cache");
        OutputStream os = response.getOutputStream();
        for (int i = 0; i < SSE_CHUNK_COUNT; i++) {
            StringBuilder chunkBuf = new StringBuilder(SSE_CHUNK_SIZE);
            chunkBuf.append("{\"chunk\":").append(i).append(",\"data\":\"");
            while (chunkBuf.length() < SSE_CHUNK_SIZE - 2) {
                chunkBuf.append("0123456789");
            }
            chunkBuf.append("\"}");
            chunkBuf.setLength(SSE_CHUNK_SIZE);
            String event = "data:" + chunkBuf + "\n\n";
            os.write(event.getBytes());
            os.flush();
        }
        os.close();
    }

}
