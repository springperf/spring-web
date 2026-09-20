package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 明文 HTTP/2（h2c，prior knowledge）E2E：{@code server.http2.enabled=true} 且无 TLS 时，
 * 管线以前言论（HTTP/2 connection preface）探测协议——命中则走 h2，否则回退 HTTP/1.1。
 *
 * <p>覆盖：h2 上的 GET/POST/HEAD、单连接并发多路复用、大响应体分帧、错误响应、
 * 响应头合法性（HTTP/2 禁止 connection-specific 头）以及同端口 HTTP/1.1 回退。</p>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, Http2H2cE2eTest.H2cConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.http2.enabled=true"
        })
class Http2H2cE2eTest {

    /** 仅 h2c prior knowledge：不协商，直接以 h2 发送前言（与框架的明文探测分支匹配）。 */
    private static final OkHttpClient H2C = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.H2_PRIOR_KNOWLEDGE))
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    /** 普通 HTTP/1.1 客户端：验证同端口回退。 */
    private static final OkHttpClient H1 = new OkHttpClient.Builder()
            .protocols(List.of(Protocol.HTTP_1_1))
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void h2c_get_servedOverHttp2() throws Exception {
        Response resp = H2C.newCall(new Request.Builder().url(url("/e2e-h2/plain")).get().build()).execute();
        try {
            assertEquals(200, resp.code());
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, resp.protocol(),
                    "请求应通过 HTTP/2 完成（前端探测未生效会退化成 HTTP/1.1）");
            assertEquals("h2-plain", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void h2c_post_withBody_echoesLength() throws Exception {
        Response resp = H2C.newCall(new Request.Builder()
                .url(url("/e2e-h2/echo"))
                .post(okhttp3.RequestBody.create("h2-body-12345", MediaType.parse("text/plain")))
                .build()).execute();
        try {
            assertEquals(200, resp.code());
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, resp.protocol());
            assertEquals("echo:13", resp.body().string(),
                    "HTTP/2 下请求体长度应被正确解析");
        } finally {
            resp.close();
        }
    }

    @Test
    void h2c_head_noBodyButContentLength() throws Exception {
        Response resp = H2C.newCall(new Request.Builder().url(url("/e2e-h2/plain")).head().build()).execute();
        try {
            assertEquals(200, resp.code());
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, resp.protocol());
            // RFC 9110 §9.3.2：HEAD 响应应携带与 GET 相同的 Content-Length（本体为空）。
            // OkHttp 对 HEAD 的 body().contentLength() 恒为 0，故直接读响应头。
            assertEquals("8", resp.header("Content-Length"),
                    "HEAD 应保留真实 Content-Length（'h2-plain' 长度 8），实际 headers=" + resp.headers());
            assertEquals("", resp.body().string(), "HEAD 不应返回 body");
        } finally {
            resp.close();
        }
    }

    @Test
    void h2c_responseHeaders_containNoConnectionSpecificHeaders() throws Exception {
        Response resp = H2C.newCall(new Request.Builder().url(url("/e2e-h2/plain")).get().build()).execute();
        try {
            assertEquals(200, resp.code());
            // RFC 9113 §8.2.2：HTTP/2 禁止 Connection / Keep-Alive / Transfer-Encoding /
            // Proxy-Connection / Upgrade；框架在 HTTP/1.1 路径会写 Connection: keep-alive，
            // 该头若泄漏到 h2 会被客户端判为协议错误（OkHttp 会直接抛 ProtocolException）。
            for (String forbidden : List.of("Connection", "Keep-Alive", "Transfer-Encoding",
                    "Proxy-Connection", "Upgrade")) {
                assertTrue(resp.header(forbidden) == null,
                        "HTTP/2 响应不得含 " + forbidden + " 头，实际 " + resp.headers());
            }
        } finally {
            resp.close();
        }
    }

    @Test
    void h2c_largeResponseBody_completeAndCorrect() throws Exception {
        Response resp = H2C.newCall(new Request.Builder().url(url("/e2e-h2/large")).get().build()).execute();
        try {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals(40000, body.length(),
                    "跨多帧的大响应体应完整（Content-Length 帧语义）");
            assertTrue(body.startsWith("L") && body.endsWith("L"), "首尾字符应保持完整");
        } finally {
            resp.close();
        }
    }

    @Test
    void h2c_notFound_returns404() throws Exception {
        Response resp = H2C.newCall(new Request.Builder()
                .url(url("/e2e-h2/definitely-missing")).get().build()).execute();
        try {
            assertEquals(404, resp.code());
            assertEquals(Protocol.H2_PRIOR_KNOWLEDGE, resp.protocol(),
                    "错误响应也应经 h2 返回（协议层不得降级或断流）");
        } finally {
            resp.close();
        }
    }

    @Test
    void h2c_concurrentStreams_allServedOnOneConnection() throws Exception {
        int concurrency = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(concurrency);
        AtomicInteger ok = new AtomicInteger();
        List<String> failures = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    Response resp = H2C.newCall(new Request.Builder()
                            .url(url("/e2e-h2/slow?i=" + idx)).get().build()).execute();
                    try {
                        if (resp.code() == 200 && ("slow-" + idx).equals(resp.body().string())
                                && resp.protocol() == Protocol.H2_PRIOR_KNOWLEDGE) {
                            ok.incrementAndGet();
                        } else {
                            failures.add("idx=" + idx + " code=" + resp.code() + " proto=" + resp.protocol());
                        }
                    } finally {
                        resp.close();
                    }
                } catch (Exception e) {
                    failures.add("idx=" + idx + " ex=" + e);
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "并发 h2 请求应在超时内完成");
        assertEquals(concurrency, ok.get(),
                "同一连接上的多路复用流应各自正确响应，失败详情=" + failures);
    }

    @Test
    void samePort_http11Fallback_stillWorks() throws Exception {
        Response resp = H1.newCall(new Request.Builder().url(url("/e2e-h2/plain")).get().build()).execute();
        try {
            assertEquals(200, resp.code(),
                    "启用 h2c 后同端口仍须服务 HTTP/1.1 客户端（前言探测未命中即回退）");
            assertEquals(Protocol.HTTP_1_1, resp.protocol());
            assertEquals("h2-plain", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class H2cConfig {
        @Bean
        H2cController h2cController() {
            return new H2cController();
        }
    }

    @RestController
    static class H2cController {

        @GetMapping("/e2e-h2/plain")
        public String plain() {
            return "h2-plain";
        }

        @PostMapping("/e2e-h2/echo")
        public String echo(@RequestBody(required = false) String body) {
            return "echo:" + (body == null ? 0 : body.length());
        }

        @GetMapping("/e2e-h2/large")
        public String large() {
            return "L" + "x".repeat(39998) + "L";
        }

        @GetMapping("/e2e-h2/slow")
        public String slow(@org.springframework.web.bind.annotation.RequestParam int i) {
            return "slow-" + i;
        }
    }
}
