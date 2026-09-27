package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 优雅关闭 E2E（{@code NettyHttpServer} SmartLifecycle 停止链路）：
 * <ol>
 * <li>关闭触发后在途**渐进式**请求必须完整送达（首段已发 + 尾段 + 终止块 + 连接关闭）， 不得因关闭把流拦腰截断；</li>
 * <li>已建立的 keep-alive 连接上的新请求被拒绝：503 Service Unavailable；</li>
 * <li>关闭完成后新连接被拒绝（acceptor 已关，TCP 连接失败）。</li>
 * </ol>
 * <p>
 * 时序同步：在途请求睡 2s，业务池排空必须等它结束（&gt;5s 上限内），这给 「关闭已触发但 EventLoop 尚存」的 503 断言提供了一个确定窗口。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        GracefulShutdownE2eTest.Cfg.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.shutdown.grace-period=10s" })
class GracefulShutdownE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(2))
            .readTimeout(Duration.ofSeconds(5)).build();

    @LocalServerPort
    int port;

    @Autowired
    io.springperf.web.server.NettyHttpServer server;

    private static void write(Socket s, String raw) throws IOException {
        s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
    }

    /** 读到指定标记出现为止（用于「首段已到达」的时序点）。 */
    private static String readUntil(Socket s, String marker, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        StringBuilder sb = new StringBuilder();
        InputStream in = s.getInputStream();
        byte[] buf = new byte[1024];
        while (System.currentTimeMillis() < deadline) {
            if (sb.indexOf(marker) >= 0) {
                return sb.toString();
            }
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 读到 EOF 为止（响应以连接关闭结束的场景）。 */
    private static String readAll(Socket s) throws IOException {
        StringBuilder sb = new StringBuilder();
        InputStream in = s.getInputStream();
        byte[] buf = new byte[4096];
        while (true) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** 精确读取**一个**完整响应（keep-alive 连接上逐个消费响应，不污染后续轮询）。 */
    private static String readOneResponse(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        // 逐字节读头，滚动窗口匹配 \r\n\r\n
        final String CRLFCRLF = "\r\n\r\n";
        while (!head.toString().endsWith(CRLFCRLF)) {
            int c = in.read();
            if (c < 0) {
                return head.toString();
            }
            head.append((char) c);
        }
        int cl = -1;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                cl = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        StringBuilder body = new StringBuilder();
        if (cl > 0) {
            byte[] buf = new byte[cl];
            int read = 0;
            while (read < cl) {
                int n = in.read(buf, read, cl - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            body.append(new String(buf, 0, read, StandardCharsets.UTF_8));
        }
        return head.toString() + body;
    }

    @Test
    void gracefulShutdown_inFlightProgressiveCompletes_newRequestsRejected() throws Exception {
        // ---- 连接 A：在途渐进式请求（首段立即送达，尾段在 2s 后） ----
        Socket a = new Socket("localhost", port);
        a.setSoTimeout(15000);
        write(a, "GET /e2e-gs/staged HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        String first = readUntil(a, "gs-part-1", 5000);
        assertTrue(first.contains("HTTP/1.1 200"), "应已提交响应头，实际:\n" + first);
        assertTrue(first.contains("gs-part-1"), "首段应已送达（渐进式），实际:\n" + first);

        // ---- 连接 B：保活空闲连接（先完成一个快速请求，精确消费整响应） ----
        Socket b = new Socket("localhost", port);
        b.setSoTimeout(8000);
        write(b, "GET /e2e-gs/ping HTTP/1.1\r\nHost: localhost\r\n\r\n");
        String ping = readOneResponse(b);
        assertTrue(ping.contains("200") && ping.contains("pong"), "关闭前请求应正常，实际:\n" + ping);

        // ---- 触发优雅关闭（直接走服务器的 SmartLifecycle 停止链路：置位 shuttingDown + 停止 accept） ----
        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            server.stop(closed::countDown);
        });
        closer.start();

        // ---- 已建立的 keep-alive 连接上的新请求 → 503 ----
        // 不带 Connection: close 的轮询保住连接复用；503 响应自带 Connection: close（EOF 结束）。
        // 窗口：shuttingDown 置位到 EventLoop 关闭之间 ≥ 在途请求的 2s（业务池排空先行）。
        String rejected = null;
        // 8s 窗口（原 3s）：宽限期本身 ≥ 在途请求剩余时长（2s），整包运行时再加 JVM 调度抖动余量。
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            String resp;
            try {
                write(b, "GET /e2e-gs/ping HTTP/1.1\r\nHost: localhost\r\n\r\n");
                resp = readOneResponse(b);
            } catch (IOException e) {
                break;
            }
            if (resp.contains("503")) {
                rejected = resp;
                break;
            }
            if (resp.isEmpty()) {
                break;
            }
        }
        assertTrue(rejected != null && rejected.contains("503"), "关闭触发后新请求应被 503 拒绝，实际:\n" + rejected);

        // ---- 在途渐进式请求必须完整送达（关闭不得截断流） ----
        String rest = readAll(a);
        String full = first + rest;
        assertTrue(full.contains("gs-part-2"), "在途渐进式请求应在优雅关闭期间完整送达，实际:\n" + full);
        assertTrue(full.contains("connection: close") || full.contains("Connection: close"),
                "在途请求应以关闭连接收尾，实际:\n" + full);
        a.close();

        // ---- 关闭完成后：新连接被拒绝（acceptor 已关） ----
        assertTrue(closed.await(30, TimeUnit.SECONDS), "上下文关闭应在宽限期内完成");
        assertThrows(Exception.class,
                () -> CLIENT
                        .newCall(new okhttp3.Request.Builder().url("http://localhost:" + port + "/e2e-gs/ping").build())
                        .execute(),
                "关闭完成后新连接应失败（停止接受连接）");
        closer.join(5000);
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        GsController gsController() {
            return new GsController();
        }
    }

    @RestController
    static class GsController {

        @GetMapping("/e2e-gs/staged")
        public void staged(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("gs-part-1\n");
            response.getWriter().flush();
            Thread.sleep(2000);
            response.getWriter().write("gs-part-2\n");
            response.getWriter().flush();
        }

        @GetMapping("/e2e-gs/ping")
        public String ping() {
            return "pong";
        }
    }
}
