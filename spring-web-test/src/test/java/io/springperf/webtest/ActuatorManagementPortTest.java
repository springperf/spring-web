package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Actuator 管理端口隔离集成测试。
 * <p>验证 {@code management.server.port=9091} 时：
 * <ul>
 *   <li>主端口（9090）不提供 Actuator 端点</li>
 *   <li>管理端口（9091）提供 Actuator 端点</li>
 *   <li>管理端口的非 Actuator 路径返回 404</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
        "server.port=9091",
        "server.servlet.context-path=/api",
        "management.endpoints.web.exposure.include=*",
        "management.server.port=9093"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ActuatorManagementPortTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void mainPort_shouldNotServeActuator() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9091/api/actuator/health")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(),
                    "Main port should not serve actuator endpoints when management port is set");
        }
    }

    @Test
    void managementPort_shouldServeHealth() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9093/actuator/health")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("UP", body.get("status"));
        }
    }

    @Test
    void managementPort_shouldServeLinks() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9093/actuator")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertNotNull(body.get("_links"), "Management port should return links");
        }
    }

    @Test
    void managementPort_nonActuatorPath_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9093/some/random/path")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(),
                    "Management port should return 404 for non-actuator paths");
        }
    }

    @Test
    void managementPort_unknownActuatorEndpoint_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url("http://localhost:9093/actuator/nonexistent")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(),
                    "Management port should return 404 for unknown actuator endpoints");
        }
    }
}