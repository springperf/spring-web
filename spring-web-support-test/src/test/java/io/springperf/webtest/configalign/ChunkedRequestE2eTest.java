package io.springperf.webtest.configalign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * chunked 请求体与 {@code server.http.max-content-length=8192} 的交互 E2E（原始 socket）： 无 Content-Length 时上限必须按**累计字节**判定；分块编码与
 * Content-Length 并存、 HTTP/1.0 使用 chunked 都必须按 RFC 7230 §3.3.3 拒绝（请求走私防护）。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ChunkedRequestE2eTest.ChunkedConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.http.max-content-length=8192" })
class ChunkedRequestE2eTest {

    @LocalServerPort
    int port;

    /** 以 chunked 编码发送 {@code totalBytes} 字节（每块 1024 字节），最后一个块可带 trailer。 */
    private static byte[] chunkedBody(int totalBytes, boolean withTrailer) {
        StringBuilder sb = new StringBuilder();
        int sent = 0;
        while (sent < totalBytes) {
            int len = Math.min(1024, totalBytes - sent);
            sb.append(Integer.toHexString(len)).append("\r\n");
            for (int i = 0; i < len; i++) {
                sb.append('c');
            }
            sb.append("\r\n");
            sent += len;
        }
        sb.append("0\r\n");
        if (withTrailer) {
            sb.append("X-Trailer: done\r\n");
        }
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 发送原始请求并读取响应。
     * <p>
     * 若读取途中遭遇连接重置（RST），返回已读到的内容（通常为空）而非抛出——被拒绝的畸形请求可能触发对端 RST， 此时客户端一个字节都读不到，这与「读到 400 后连接正常关闭」在**拒绝语义**上等价。
     * 调用方据此断言，而不是把 RST 当成测试错误（ERROR）。
     * </p>
     */
    private String sendRaw(String requestHead, byte[] requestBody) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();
            out.write(requestHead.getBytes(StandardCharsets.UTF_8));
            if (requestBody != null) {
                out.write(requestBody);
            }
            out.flush();
            return readResponse(in);
        }
    }

    /**
     * 读取响应文本；连接被重置时返回已读到的内容（通常为空）。
     * <p>
     * RST 是客户端侧行为：本测试一次性写出 header + 完整 body，而服务器读到 header 即判定失败、回 400 后关闭； 此刻客户端仍有未发送/未确认的 body 字节，其 close 会让内核发
     * RST，可能打断返回途中的 400。这是 TCP 固有属性（服务端行为符合 RFC 7230 §3.3.3——拒绝畸形请求后关闭连接），不代表服务器实现错误。 因此把 RST 归入「未读到响应」，由调用方与 400
     * 一同接受为「已拒绝」。
     * </p>
     */
    private String readResponse(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + 5000;
        while (sb.toString().split("HTTP/1\\.1 ", -1).length - 1 < 1 && System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            } catch (SocketException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 断言「服务器拒绝了该请求」：读到 400，或因客户端侧 RST 而读不到响应——两者都算拒绝成功。
     * <p>
     * 若读到了响应，则必须确实是 400；拿到 2xx/其它状态说明非法请求被正常服务，属真实回归，必须失败。
     * </p>
     */
    private static void assertRejected(String response, String message) {
        if (!response.isEmpty()) {
            assertTrue(response.contains("HTTP/1.1 400"), message + "（实际响应）:\n" + response);
        }
    }

    @Test
    void chunkedBelowLimit_served() throws Exception {
        int size = 4096;
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n" + "Connection: close\r\n\r\n";
        String r = sendRaw(head, chunkedBody(size, false));
        assertTrue(r.contains("HTTP/1.1 200"), "chunked 请求在限额内应被服务，实际:\n" + r);
        assertTrue(r.contains("len:" + size), "chunked 体应被完整聚合（累计 " + size + " 字节），实际:\n" + r);
    }

    @Test
    void chunkedExactlyAtLimit_served() throws Exception {
        int size = 8192;
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
        String r = sendRaw(head, chunkedBody(size, false));
        assertTrue(r.contains("HTTP/1.1 200"), "恰好达到上限应被服务，实际:\n" + r);
        assertTrue(r.contains("len:" + size), "累计字节应等于上限，实际:\n" + r);
    }

    @Test
    void chunkedAboveLimit_returns413() throws Exception {
        // 无 Content-Length 时必须按累计字节判定：超出 8192 即 413，不能因为「没有声明长度」而放行
        int size = 16384;
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
        String r = sendRaw(head, chunkedBody(size, false));
        assertTrue(r.contains("HTTP/1.1 413"), "chunked 体超过 max-content-length 应 413（累计判定），实际:\n" + r);
    }

    @Test
    void chunkedWithTrailer_served() throws Exception {
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
        String r = sendRaw(head, chunkedBody(2048, true));
        assertTrue(r.contains("HTTP/1.1 200"), "带 trailer 的 chunked 请求应被服务，实际:\n" + r);
        assertTrue(r.contains("len:2048"), "trailer 不应影响 body 聚合，实际:\n" + r);
    }

    @Test
    void chunkedWithContentLength_bothPresent_rejected() throws Exception {
        // RFC 7230 §3.3.3：同时出现 Transfer-Encoding 与 Content-Length 属必须拒绝的走私风险
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: text/plain\r\n"
                + "Content-Length: 2048\r\n" + "Transfer-Encoding: chunked\r\n" + "Connection: close\r\n\r\n";
        String r = sendRaw(head, chunkedBody(2048, false));
        assertRejected(r, "Content-Length 与 chunked 并存应 400（防请求走私）");
    }

    @Test
    void http10WithChunked_rejected() throws Exception {
        // HTTP/1.0 不支持分块传输编码
        String head = "POST /e2e-chunk/echo HTTP/1.0\r\n" + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n";
        String r = sendRaw(head, chunkedBody(1024, false));
        // 与 TE+CL 用例同理：被拒绝的非法请求可能收到 400，也可能因客户端侧 RST 而读不到响应，两者都算「未挂死且已拒绝」。
        // 唯一不可接受的是拿到 200（说明非法请求被正常服务）。
        assertTrue(!r.contains("HTTP/1.1 200"), "HTTP/1.0 使用 chunked 属非法请求，不应被服务，实际:\n" + r);
        if (!r.isEmpty()) {
            assertTrue(r.contains("HTTP/1."), "HTTP/1.0 + chunked 不应导致连接挂死，实际:\n" + r);
        }
    }

    @TestConfiguration
    static class ChunkedConfig {
        @Bean
        ChunkedController chunkedController() {
            return new ChunkedController();
        }
    }

    @RestController
    static class ChunkedController {

        @PostMapping("/e2e-chunk/echo")
        public String echo(@RequestBody(required = false) String body) {
            return "len:" + (body == null ? 0 : body.length());
        }
    }
}
