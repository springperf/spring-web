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
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

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

    @Test
    void chunkedBelowLimit_served() throws Exception {
        int size = 4096;
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n" + "Connection: close\r\n\r\n";
        String resp = sendRaw(head, chunkedBody(size, false));
        assertTrue(resp.contains("HTTP/1.1 200"), "chunked 请求在限额内应被服务，实际:\n" + resp);
        assertTrue(resp.contains("len:" + size), "chunked 体应被完整聚合（累计 " + size + " 字节），实际:\n" + resp);
    }

    @Test
    void chunkedExactlyAtLimit_served() throws Exception {
        int size = 8192;
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
        String resp = sendRaw(head, chunkedBody(size, false));
        assertTrue(resp.contains("HTTP/1.1 200"), "恰好达到上限应被服务，实际:\n" + resp);
        assertTrue(resp.contains("len:" + size), "累计字节应等于上限，实际:\n" + resp);
    }

    @Test
    void chunkedAboveLimit_returns413() throws Exception {
        // 无 Content-Length 时必须按累计字节判定：超出 8192 即 413，不能因为「没有声明长度」而放行
        int size = 16384;
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
        String resp = sendRaw(head, chunkedBody(size, false));
        assertTrue(resp.contains("HTTP/1.1 413"), "chunked 体超过 max-content-length 应 413（累计判定），实际:\n" + resp);
    }

    @Test
    void chunkedWithTrailer_served() throws Exception {
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n";
        String resp = sendRaw(head, chunkedBody(2048, true));
        assertTrue(resp.contains("HTTP/1.1 200"), "带 trailer 的 chunked 请求应被服务，实际:\n" + resp);
        assertTrue(resp.contains("len:2048"), "trailer 不应影响 body 聚合，实际:\n" + resp);
    }

    @Test
    void chunkedWithContentLength_bothPresent_rejected() throws Exception {
        // RFC 7230 §3.3.3：同时出现 Transfer-Encoding 与 Content-Length 属必须拒绝的走私风险
        String head = "POST /e2e-chunk/echo HTTP/1.1\r\n" + "Host: localhost\r\n" + "Content-Type: text/plain\r\n"
                + "Content-Length: 2048\r\n" + "Transfer-Encoding: chunked\r\n" + "Connection: close\r\n\r\n";
        String resp = sendRaw(head, chunkedBody(2048, false));
        assertTrue(resp.contains("HTTP/1.1 400"), "Content-Length 与 chunked 并存应 400（防请求走私），实际:\n" + resp);
    }

    @Test
    void http10WithChunked_rejected() throws Exception {
        // HTTP/1.0 不支持分块传输编码
        String head = "POST /e2e-chunk/echo HTTP/1.0\r\n" + "Content-Type: text/plain\r\n"
                + "Transfer-Encoding: chunked\r\n\r\n";
        String resp = sendRaw(head, chunkedBody(1024, false));
        assertTrue(resp.contains("HTTP/1."), "HTTP/1.0 + chunked 不应导致连接挂死，实际:\n" + resp);
        assertTrue(!resp.contains("HTTP/1.1 200") || resp.contains("400"), "HTTP/1.0 使用 chunked 属非法请求，实际:\n" + resp);
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
