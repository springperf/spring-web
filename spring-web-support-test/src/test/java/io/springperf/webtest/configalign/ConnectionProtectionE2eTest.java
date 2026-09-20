package io.springperf.webtest.configalign;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 连接层防护 E2E（原始 socket）：
 *
 * <ul>
 *   <li>{@code server.max-connections}：限额内正常服务；超限的 TCP 连接直接关闭（无 HTTP 响应）；
 *       连接释放后新连接可被接受（计数不泄漏）；</li>
 *   <li>{@code server.http.read-timeout}：空闲连接在超时后被关闭；半截请求头也会被回收；</li>
 *   <li>关键语义：**处理中的请求不得被读超时掐断**——读超时应只约束「读空闲」，
 *       长耗时处理器（慢 SQL/远程调用）仍须把响应送达。</li>
 * </ul>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ConnectionProtectionE2eTest.ProtectionConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.max-connections=2",
                "server.http.read-timeout=1s"
        })
class ConnectionProtectionE2eTest {

    @LocalServerPort
    int port;

    // ==================== 工具 ====================

    private Socket open() throws IOException {
        Socket s = new Socket("localhost", port);
        s.setSoTimeout(5000);
        return s;
    }

    private static void write(Socket s, String raw) throws IOException {
        OutputStream out = s.getOutputStream();
        out.write(raw.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** 读取直至 EOF 或超时，返回已读文本。 */
    private static String readUntilEof(Socket s) throws IOException {
        InputStream in = s.getInputStream();
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

    /** 等待连接被服务端关闭（读得 EOF）；超时返回 false。 */
    private static boolean awaitEof(Socket s, long millis) throws IOException {
        s.setSoTimeout((int) millis);
        try {
            return s.getInputStream().read() < 0;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    /**
     * 精确读取**一个**完整响应（按 {@code Content-Length} 收尾），不等到 socket 读超时。
     *
     * <p>用于限额类断言：若用 {@link #readUntilEof}，两条连接会各等满 {@code SO_TIMEOUT}，
     * 期间 {@code server.http.read-timeout=1s} 会把它们当空闲连接回收、腾出限额，
     * 于是"超限连接应被拒"的断言假性失败。</p>
     */
    private static String readOneResponse(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0) {
            head.append((char) b);
            if (head.length() >= 4 && head.charAt(head.length() - 4) == '\r'
                    && head.charAt(head.length() - 3) == '\n'
                    && head.charAt(head.length() - 2) == '\r'
                    && head.charAt(head.length() - 1) == '\n') {
                break;
            }
        }
        String headers = head.toString();
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("content-length")) {
                contentLength = Integer.parseInt(line.substring(colon + 1).trim());
            }
        }
        if (contentLength <= 0) {
            return headers;
        }
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return headers + new String(body, 0, read, StandardCharsets.UTF_8);
    }

    private static String okRequest() {
        return "GET /e2e-proto/ok HTTP/1.1\r\nHost: localhost\r\n\r\n";
    }

    /**
     * 每个用例开始前确认限额已归零。
     *
     * <p>{@code server.max-connections=2} 是**类级共享**配置：上一个用例留下的连接（或服务端尚未
     * 回收的计数）会让"本用例独占 2 个槽位"的前提不成立，表现为与本用例语义无关的假失败。
     * 此处用一次性裸连接轮询到可服务为止（{@link #awaitServiceable()} 内部已含截止时间）。</p>
     */
    @BeforeEach
    void waitSlotsFree() throws Exception {
        assertTrue(awaitServiceable(), "用例开始前应能建立新连接（槽位应已释放）");
    }

    /**
     * 轮询直到新连接可被服务（连接计数释放的最终一致性断言）。
     *
     * <p>刻意用**一次性裸连接**而非共享 OkHttp 客户端：OkHttp 会维持空闲连接池，
     * 池中连接同样占用 {@code server.max-connections}，会让限额断言假性失败。</p>
     */
    private boolean awaitServiceable() throws Exception {
        // 8s（原 3s）：整包运行时连接回收 + 业务池排空可能远慢于 3s，曾导致"释放后应能继续服务"假失败
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = open()) {
                write(s, "GET /e2e-proto/ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
                if (readUntilEof(s).contains("200")) {
                    return true;
                }
            } catch (IOException ignored) {
                // 连接被拒（限额未释放）：稍后重试
            }
            Thread.sleep(50);
        }
        return false;
    }

    // ==================== server.max-connections ====================

    @Test
    void withinLimit_connectionsServed() throws Exception {
        try (Socket a = open(); Socket b = open()) {
            write(a, okRequest());
            write(b, okRequest());
            assertTrue(readUntilEof(a).contains("200"), "限额内的第 1 个连接应被服务");
            assertTrue(readUntilEof(b).contains("200"), "限额内的第 2 个连接应被服务");
        }
        assertTrue(awaitServiceable(), "释放后应能继续服务");
    }

    @Test
    void overLimit_extraConnectionClosedWithoutHttpResponse() throws Exception {
        try (Socket a = open(); Socket b = open()) {
            write(a, okRequest());
            write(b, okRequest());
            // 必须「精确读完整响应」，不能等 socket 读超时：readUntilEof 会各等满 5s，
            // 期间 server.http.read-timeout=1s 会把这两条空闲连接回收、腾出限额，
            // 第 3 个连接就不再被拒（本用例曾在整包运行中因此假失败）。
            assertTrue(readOneResponse(a).contains("200"));
            assertTrue(readOneResponse(b).contains("200"));

            // 第 3 个连接：超过 server.max-connections=2，应在 TCP 层被直接关闭
            try (Socket c = open()) {
                c.setSoTimeout(3000);
                assertTrue(awaitEof(c, 3000),
                        "超过 server.max-connections 的连接应被服务端直接关闭（无 HTTP 响应）");
            }
        }
        assertTrue(awaitServiceable(), "释放后应能继续服务");
    }

    @Test
    void connectionClose_releasesSlot() throws Exception {
        Socket a = open();
        Socket b = open();
        try {
            write(a, "GET /e2e-proto/ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            write(b, okRequest());
            assertTrue(readUntilEof(a).contains("200"));
            assertTrue(readUntilEof(b).contains("200"));

            // a 已由服务端关闭（Connection: close）→ 计数应随之释放
            assertTrue(awaitServiceable(),
                    "客户端声明 Connection: close 后连接应被回收，腾出限额");
        } finally {
            a.close();
            b.close();
        }
    }

    @Test
    void clientClose_releasesSlot() throws Exception {
        Socket a = open();
        Socket b = open();
        try {
            write(a, okRequest());
            write(b, okRequest());
            assertTrue(readUntilEof(a).contains("200"));
            assertTrue(readUntilEof(b).contains("200"));
        } finally {
            a.close();
        }
        assertTrue(awaitServiceable(), "客户端主动断开后连接计数应释放（不得泄漏导致后续全部拒绝）");
        b.close();
    }

    // ==================== server.http.read-timeout ====================

    @Test
    void idleConnection_closedAfterReadTimeout() throws Exception {
        try (Socket s = open()) {
            // 建连后不发任何数据：超过 read-timeout(1s) 应被回收
            assertTrue(awaitEof(s, 4000),
                    "空闲连接应在 server.http.read-timeout 后被关闭");
        }
    }

    @Test
    void partialRequestHeaders_closedAfterReadTimeout() throws Exception {
        try (Socket s = open()) {
            write(s, "GET /e2e-proto/ok HTTP/1.1\r\nHost: localhost\r\n"); // 未以空行结束
            assertTrue(awaitEof(s, 4000),
                    "半截请求头（无终止空行）应在 read-timeout 后被回收，避免慢速攻击占满连接");
        }
    }

    @Test
    void keepAliveIdle_afterResponse_closedByReadTimeout() throws Exception {
        try (Socket s = open()) {
            write(s, okRequest());
            assertTrue(readUntilEof(s).contains("200"), "首个请求应正常响应");
            // 复用连接但不发新请求：空闲超过 read-timeout 应被回收
            assertTrue(awaitEof(s, 4000),
                    "保持连接空闲超过 read-timeout 应被关闭");
        }
    }

    @Test
    void inFlightRequest_slowHandler_stillDeliversResponse() throws Exception {
        // 处理耗时 1.5s > read-timeout(1s)：读超时只应约束「读空闲」，
        // 不得掐断正在处理中的请求（否则任何超过 read-timeout 的慢接口都会被断连）
        try (Socket s = open()) {
            // 读窗口放宽到 15s：整包套件运行时 JVM 受 GC/调度挤压，1.5s 的处理器可能显著延迟，
            // 5s（open() 的默认值）曾在全量运行中假失败。放宽只影响客户端等待上限，
            // 不改变被断言的服务端语义。
            s.setSoTimeout(15000);
            write(s, "GET /e2e-proto/slow?ms=1500 HTTP/1.1\r\nHost: localhost\r\n\r\n");
            String resp = readUntilEof(s);
            assertTrue(resp.contains("HTTP/1.1 200"),
                    "慢处理器（1.5s > read-timeout 1s）应仍能返回响应，实际:\n" + resp);
            assertTrue(resp.contains("slow-done"), "响应体应完整送达，实际:\n" + resp);
        }
    }

    @Test
    void inFlightRequest_verySlowHandler_acrossMultipleTimeoutWindows() throws Exception {
        // 3s > 3 个 read-timeout 窗口：验证超时窗口不会累积触发断连
        try (Socket s = open()) {
            s.setSoTimeout(8000);
            write(s, "GET /e2e-proto/slow?ms=3000 HTTP/1.1\r\nHost: localhost\r\n\r\n");
            String resp = readUntilEof(s);
            assertTrue(resp.contains("HTTP/1.1 200"),
                    "跨多个读超时窗口的慢请求仍应送达响应，实际:\n" + resp);
        }
    }

    @TestConfiguration
    static class ProtectionConfig {
        @Bean
        ProtectionController protectionController() {
            return new ProtectionController();
        }
    }

    @RestController
    static class ProtectionController {

        @GetMapping("/e2e-proto/ok")
        public String ok() {
            return "ok-body";
        }

        @GetMapping("/e2e-proto/slow")
        public String slow(@RequestParam long ms) throws InterruptedException {
            Thread.sleep(ms);
            return "slow-done";
        }
    }
}
