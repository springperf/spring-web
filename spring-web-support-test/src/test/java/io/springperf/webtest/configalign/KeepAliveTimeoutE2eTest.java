package io.springperf.webtest.configalign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.keep-alive-timeout} E2E：连接空闲超过超时值后由服务端主动关闭
 * （真实 KeepAliveHandler 空闲检测链路）。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                KeepAliveTimeoutE2eTest.KeepAliveConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.keep-alive-timeout=500ms")
class KeepAliveTimeoutE2eTest {

    @LocalServerPort
    int port;

    @Test
    void idleConnection_closedByServerAfterTimeout() throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            out.write(("GET /api/e2e-keepalive/hit HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            // 读完整响应（body "ok" 结束标记）
            StringBuilder first = new StringBuilder();
            byte[] buf = new byte[1024];
            while (!first.toString().contains("ok")) {
                int n = in.read(buf);
                if (n < 0) {
                    break;
                }
                first.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            assertTrue(first.toString().contains("200"), "首个请求应正常响应:\n" + first);

            // 空闲 1.2s（> 500ms 超时）后服务端应已关闭连接：read 返回 -1
            Thread.sleep(1200);
            int n = in.read(buf);
            assertEquals(-1, n,
                    "空闲超过 keep-alive-timeout 后服务端应关闭连接（read=-1），实际读到 "
                            + (n < 0 ? "EOF" : n + " 字节"));
        }
    }

    @TestConfiguration
    static class KeepAliveConfig {
        @Bean
        KeepAliveHitController keepAliveHitController() {
            return new KeepAliveHitController();
        }
    }

    @RestController
    static class KeepAliveHitController {
        @GetMapping("/e2e-keepalive/hit")
        public String hit() {
            return "ok";
        }
    }
}
