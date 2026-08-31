package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import io.springperf.web.autoconfigure.actuator.server.ManagementNettyHttpServer;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Actuator 管理端口隔离集成测试。
 * <p>验证管理端口隔离时：
 * <ul>
 *   <li>主端口（RANDOM）不提供 Actuator 端点</li>
 *   <li>管理端口（随机空闲端口）提供 Actuator 端点</li>
 *   <li>管理端口的非 Actuator 路径返回 404</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
        "server.servlet.context-path=/api",
        "management.endpoints.web.exposure.include=*"
})
@ContextConfiguration(initializers = ActuatorManagementPortTest.ManagementPortInitializer.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ActuatorManagementPortTest {

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

    private String mainUrl(String path) {
        return "http://localhost:" + mainPort + path;
    }

    private String managementUrl(String path) {
        return "http://localhost:" + managementServer.getActualPort() + path;
    }

    @Test
    void mainPort_shouldNotServeActuator() throws Exception {
        Request req = new Request.Builder()
                .url(mainUrl("/api/actuator/health"))
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
                .url(managementUrl("/actuator/health"))
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
                .url(managementUrl("/actuator"))
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
                .url(managementUrl("/some/random/path"))
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
                .url(managementUrl("/actuator/nonexistent"))
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(),
                    "Management port should return 404 for unknown actuator endpoints");
        }
    }
}