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
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Actuator 绠＄悊绔彛闅旂闆嗘垚娴嬭瘯銆?
 * <p>楠岃瘉绠＄悊绔彛闅旂鏃讹細
 * <ul>
 *   <li>涓荤鍙ｏ紙RANDOM锛変笉鎻愪緵 Actuator 绔偣</li>
 *   <li>绠＄悊绔彛锛堥殢鏈虹┖闂茬鍙ｏ級鎻愪緵 Actuator 绔偣</li>
 *   <li>绠＄悊绔彛鐨勯潪 Actuator 璺緞杩斿洖 404</li>
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

    /** 鍚姩鍓嶅垎閰嶄竴涓┖闂茬鍙ｄ綔涓虹鐞嗙鍙ｏ紝閬垮厤鍥哄畾绔彛琚崰鐢?鍐茬獊 */
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