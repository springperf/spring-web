package io.springperf.webtest.configalign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.http.timeout=1s} 响应超时 E2E（原始 socket，便于检查「只发出一个响应」）：
 *
 * <ul>
 *   <li>处理超过时限 → 504 Gateway Timeout；</li>
 *   <li>超时后处理器才完成时，**不得再写出第二个响应**（客户端只能收到一个完整响应）；</li>
 *   <li>已提交（flush/流式）的响应不受该计时器影响——提交即取消响应超时；</li>
 *   <li>限额内的请求不受影响，且 504 之后连接可继续复用。</li>
 * </ul>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResponseTimeoutE2eTest.TimeoutConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.http.timeout=1s"
        })
class ResponseTimeoutE2eTest {

    @LocalServerPort
    int port;

    private static String readAll(Socket socket, long millis) throws IOException {
        socket.setSoTimeout((int) millis);
        InputStream in = socket.getInputStream();
        StringBuilder sb = new StringBuilder();
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

    private Socket open() throws IOException {
        Socket socket = new Socket("localhost", port);
        socket.setSoTimeout(8000);
        return socket;
    }

    private static void write(Socket socket, String raw) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static int countStatusLines(String raw) {
        int count = 0;
        int idx = raw.indexOf("HTTP/1.1 ");
        while (idx >= 0) {
            count++;
            idx = raw.indexOf("HTTP/1.1 ", idx + 1);
        }
        return count;
    }

    @Test
    void slowHandler_exceedingTimeout_returns504() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-timeout/slow?ms=2500 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 8000);
            assertTrue(resp.contains("HTTP/1.1 504"),
                    "处理超过 server.http.timeout 应返回 504，实际:\n" + resp);
            assertEquals(1, countStatusLines(resp), "只应有一个响应，实际:\n" + resp);
        }
    }

    @Test
    void lateCompletion_doesNotWriteSecondResponse() throws Exception {
        // 504 已写出后处理器才完成：其写入必须被丢弃，不得append 第二个响应
        try (Socket s = open()) {
            write(s, "GET /e2e-timeout/slow?ms=2500 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 8000);
            assertTrue(resp.contains("504"), "应先返回 504，实际:\n" + resp);
            assertEquals(1, countStatusLines(resp),
                    "迟到的处理器完成不得补写第二个响应，实际:\n" + resp);
            assertTrue(!resp.contains("slow-done"),
                    "迟到的响应体不得出现在客户端，实际:\n" + resp);
        }
    }

    @Test
    void fastHandler_unaffected() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-timeout/slow?ms=100 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 8000);
            assertTrue(resp.contains("HTTP/1.1 200"), "限额内的请求应正常 200，实际:\n" + resp);
            assertTrue(resp.contains("slow-done"));
        }
    }

    @Test
    void committedResponse_notRewrittenByTimeout() throws Exception {
        // 提交后响应超时必须被取消：不得把已提交的响应改写成 504，也不得产生第二个响应头
        try (Socket s = open()) {
            write(s, "GET /e2e-timeout/stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 8000);
            assertTrue(resp.contains("first-part"), "已提交的第一段应送达，实际:\n" + resp);
            assertTrue(!resp.contains("504"),
                    "已提交的响应不得被响应超时改写为 504，实际:\n" + resp);
            assertEquals(1, countStatusLines(resp), "只应有一个响应，实际:\n" + resp);
        }
    }

    @Test
    void servletFlushBuffer_streamsProgressively_laterWritesDelivered() throws Exception {
        // 对齐 Tomcat：flushBuffer() 提交响应后仍可继续写入（chunked 续帧），
        // 因此两段内容都必须送达（修复前第二段被静默丢弃）。
        try (Socket s = open()) {
            write(s, "GET /e2e-timeout/stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 8000);
            assertTrue(resp.contains("first-part"), "实际:\n" + resp);
            assertTrue(resp.contains("second-part"),
                    "flushBuffer() 后的写入应以 chunked 续帧送达，实际:\n" + resp);
            assertTrue(resp.toLowerCase().contains("transfer-encoding: chunked"),
                    "渐进式输出应使用 chunked 帧，实际:\n" + resp);
            assertEquals(1, countStatusLines(resp), "只应有一个响应，实际:\n" + resp);
        }
    }

    @Test
    void timeout504_keepsConnectionReusable() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-timeout/slow?ms=2500 HTTP/1.1\r\nHost: localhost\r\n\r\n");
            // 6s 窗口：504 应在 2.5s 左右到达，留 2.4x 余量抗整包运行时的调度抖动
            String first = readAll(s, 6000);
            assertTrue(first.contains("504"), "首个请求应 504，实际:\n" + first);

            // 连接保持可用：后续请求应正常响应（除非 504 声明了 Connection: close）
            if (first.toLowerCase().contains("connection: close")) {
                return; // 已声明关闭则不再复用
            }
            write(s, "GET /e2e-timeout/slow?ms=100 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String second = readAll(s, 6000);
            // 本用例锁定的是「504 之后同连接仍可复用」——即服务端在该连接上**受理并完整回答了**
            // 第二个请求。1s 响应超时在负载高的整包运行中可能仍被触发（100ms 的处理器被调度延迟），
            // 那是 fastHandler_unaffected 用例的职责；此处允许 200 或 504，但必须是该连接上的
            // **完整单一响应**（连接被静默关闭 → 空响应；响应拼接 → 多个状态行，都会失败）。
            assertTrue(second.contains("HTTP/1.1 "),
                    "504 之后同连接应仍可服务（200 或超时 504 均证明复用），实际:\n" + second);
            assertEquals(1, countStatusLines(second),
                    "第二个响应不得与前一个拼接，实际:\n" + second);
        }
    }

    @TestConfiguration
    static class TimeoutConfig {
        @Bean
        TimeoutController timeoutController() {
            return new TimeoutController();
        }
    }

    @RestController
    static class TimeoutController {

        @GetMapping("/e2e-timeout/slow")
        public String slow(@RequestParam long ms) throws InterruptedException {
            Thread.sleep(ms);
            return "slow-done";
        }

        /** 先提交响应再长时间处理：提交应取消响应超时。 */
        @GetMapping("/e2e-timeout/stream")
        public void stream(HttpServletResponse response) throws IOException, InterruptedException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("first-part\n");
            response.flushBuffer();
            Thread.sleep(1800);
            response.getWriter().write("second-part\n");
            response.flushBuffer();
        }
    }
}
