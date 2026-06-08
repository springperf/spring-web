package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P2 E2E 测试：基础设施边界条件。
 * <p>
 * 使用独立 Spring 上下文（端口 9093），
 * 测试 max-content-length 超限拒绝等场景。
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=9093",
                "server.servlet.context-path=",
                "server.http.max-content-length=100"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class LimitsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl = "http://localhost:9093";

    @Test
    void postLargeBody_exceedsMaxContentLength_returns413() throws Exception {
        // 构造超过 max-content-length 的请求体（200 字节 > 100 限制）
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append('x');
        }
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/save?id=test")
                .post(RequestBody.create(JSON, sb.toString()))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(413, resp.code(),
                    "Request exceeding max-content-length should return 413");
        }
    }

    @Test
    void postSmallBody_withinMaxContentLength_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/save?id=test")
                .post(RequestBody.create(JSON, "\"small\""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(),
                    "Request within max-content-length should succeed");
        }
    }
}