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
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * 拒绝类用例只发送 header（非法性在 header 阶段即可判定），不在服务端提前关闭后继续写 body， 避免客户端侧产生 RST 而打断返回途中的 400。
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

    /** 拒绝响应必须是完整状态行且状态码为 400（HTTP/1.0 与 1.1 状态行均允许）。 */
    private static final Pattern REJECTED_STATUS = Pattern.compile("^HTTP/1\\.[01] 400\\b");

    /**
     * 读取响应直到拿到状态行；读超时/连接重置一律带上下文抛出，不再静默转成「空响应 = 已拒绝」。
     */
    private String readResponse(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        long deadline = System.currentTimeMillis() + 5000;
        while (sb.indexOf("HTTP/1.") < 0 && System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf);
            } catch (java.io.IOException e) {
                throw new AssertionError("读取拒绝响应时连接异常，无法确认服务端已明确拒绝；已读内容:\n" + sb, e);
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /**
     * 断言「服务器明确拒绝了该请求」：必须解析出 400 状态行。
     * 空响应（挂起 / 静默关闭 / 一个字节都没读到）判失败，不能再算「已拒绝」。
     */
    private static void assertRejected(String response, String message) {
        assertFalse(response.isEmpty(), message + "：服务端未给出任何响应（挂起或静默关闭）");
        assertTrue(REJECTED_STATUS.matcher(response).find(), message + "（实际响应）:\n" + response);
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
        // 只发 header：TE+CL 并存的非法性在 header 阶段即可判定，继续写 body 会因对端提前关闭触发 RST。
        String r = sendRaw(head, null);
        assertRejected(r, "Content-Length 与 chunked 并存应 400（防请求走私）");
    }

    @Test
    void http10WithChunked_rejected() throws Exception {
        // HTTP/1.0 不支持分块传输编码
        String head = "POST /e2e-chunk/echo HTTP/1.0\r\n" + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n";
        // 与 TE+CL 用例同理：只发 header，且必须拿到明确的 400 状态行，不能靠「不是 200」或「读不到响应」放行。
        String r = sendRaw(head, null);
        assertRejected(r, "HTTP/1.0 使用 chunked 属非法请求，应 400 且不得挂死");
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
