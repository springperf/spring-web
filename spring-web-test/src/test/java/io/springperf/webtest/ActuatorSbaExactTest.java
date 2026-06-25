package io.springperf.webtest;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精确模拟：无 context-path，独立管理端口，SBA v2 Accept 头。
 * 验证所有 actuator 端点可被 SBA 2.3.0.1 正常访问。
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "server.port=9097",
        "management.endpoints.web.exposure.include=*",
        "management.server.port=9098"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@DirtiesContext
public class ActuatorSbaExactTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private final String mgmtBase = "http://localhost:9098/actuator";

    @ParameterizedTest
    @ValueSource(strings = {"", "/health", "/mappings", "/beans", "/env", "/metrics",
            "/caches", "/conditions", "/configprops", "/loggers", "/threaddump",
            "/scheduledtasks", "/info"})
    void sbaEndpoint_shouldSucceed(String endpoint) throws Exception {
        Request req = new Request.Builder()
                .url(mgmtBase + endpoint)
                .header("Accept", "application/vnd.spring-boot.actuator.v2+json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "<empty>";
            log.info("GET {} -> {} bodyLength={} contentType={}",
                    endpoint, resp.code(), body.length(), resp.header("Content-Type"));
            assertEquals(200, resp.code(),
                    "SBA v2 GET " + endpoint + " should return 200. Body: " + body);
            // 验证 body 能被 SBA 解析（非空 JSON）
            assertTrue(body.startsWith("{") && body.endsWith("}"),
                    "Body should be valid JSON object. Body: " + body);
        }
    }
}