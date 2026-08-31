package io.springperf.webtest;

import io.springperf.web.autoconfigure.actuator.server.ManagementNettyHttpServer;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精确模拟：无 context-path，独立管理端口，SBA v2 Accept 头。
 * 验证所有 actuator 端点可被 SBA 2.3.0.1 正常访问。
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "management.endpoints.web.exposure.include=*"
})
@ContextConfiguration(initializers = ActuatorSbaExactTest.ManagementPortInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@DirtiesContext
public class ActuatorSbaExactTest {

    /** 启动前分配一个空闲端口作为管理端口，避免固定端口被占用/冲突 */
    public static class ManagementPortInitializer
            implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertyValues.of("management.server.port=" + freePort()).applyTo(ctx.getEnvironment());
        }
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @LocalServerPort
    private int mainPort;

    @Autowired
    private ManagementNettyHttpServer managementServer;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private String mgmtBase() {
        return "http://localhost:" + managementServer.getActualPort() + "/actuator";
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/health", "/mappings", "/beans", "/env", "/metrics",
            "/caches", "/conditions", "/configprops", "/loggers", "/threaddump",
            "/scheduledtasks", "/info"})
    void sbaEndpoint_shouldSucceed(String endpoint) throws Exception {
        Request req = new Request.Builder()
                .url(mgmtBase() + endpoint)
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