package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.max-http-response-header-size=512} E2E： 响应头总量超限时降级为最小 500（{@code Content-Length: 0}，业务头全丢弃）， 未超限时正常 200
 * 且业务头完整保留。
 * <p>
 * 目的是防止业务误写海量响应头（如超大 Cookie）污染连接：超限时不写出半截响应头， 而是用一个干净的最小 500 替代。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ResponseHeaderLimitE2eTest.HeaderLimitConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.max-http-response-header-size=512" })
class ResponseHeaderLimitE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            // 上限校验失败时业务头会被丢弃：OkHttp 不应因「响应头丑陋」抛异常
            .build();

    @LocalServerPort
    int port;

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).get().build()).execute();
    }

    @Test
    void oversizedResponseHeaders_degradeToMinimal500() throws Exception {
        Response resp = get("/e2e-hdr/big");
        try {
            assertEquals(500, resp.code(), "响应头总量超过上限应降级为最小 500，实际 " + resp.code());
            assertNull(resp.header("X-Big"), "降级响应不应携带超限业务头，实际 X-Big=" + resp.header("X-Big"));
            assertEquals(0, resp.body().contentLength(), "降级响应 body 应为空（Content-Length: 0）");
            assertEquals("", resp.body().string(), "降级响应不应有 body 内容");
        } finally {
            resp.close();
        }
    }

    @Test
    void normalResponseHeaders_belowLimit_unaffected() throws Exception {
        Response resp = get("/e2e-hdr/small");
        try {
            assertEquals(200, resp.code(), "响应头未超限应正常 200");
            assertEquals("small-header-value", resp.header("X-Small"), "未超限时业务头应完整保留");
            assertEquals("small-body", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void headersExactlyNearLimit_boundaryStillPasses() throws Exception {
        // 上限 512：单头 "X-Near: <400 字符>" 合计约 410 字节，未超限 → 应 200
        Response resp = get("/e2e-hdr/near");
        try {
            assertEquals(200, resp.code(), "恰好接近但未超上限的响应头应正常写出");
            String near = resp.header("X-Near");
            assertTrue(near != null && near.length() == 400,
                    "边界内的响应头应完整保留，实际长度=" + (near == null ? "null" : near.length()));
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class HeaderLimitConfig {
        @Bean
        HeaderLimitController headerLimitController() {
            return new HeaderLimitController();
        }
    }

    @RestController
    static class HeaderLimitController {

        @GetMapping("/e2e-hdr/big")
        public ResponseEntity<String> big() {
            return ResponseEntity.ok().header("X-Big", "b".repeat(2000)).body("should-not-be-written");
        }

        @GetMapping("/e2e-hdr/small")
        public ResponseEntity<String> small() {
            return ResponseEntity.ok().header("X-Small", "small-header-value").body("small-body");
        }

        @GetMapping("/e2e-hdr/near")
        public ResponseEntity<String> near() {
            return ResponseEntity.ok().header("X-Near", "n".repeat(400)).body("near-body");
        }
    }
}
