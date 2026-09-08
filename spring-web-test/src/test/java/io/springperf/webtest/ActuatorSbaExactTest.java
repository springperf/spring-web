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
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绮剧‘妯℃嫙锛氭棤 context-path锛岀嫭绔嬬鐞嗙鍙ｏ紝SBA v2 Accept 澶淬€?
 * 楠岃瘉鎵€鏈?actuator 绔偣鍙 SBA 2.3.0.1 姝ｅ父璁块棶銆?
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
            // 楠岃瘉 body 鑳借 SBA 瑙ｆ瀽锛堥潪绌?JSON锛?
            assertTrue(body.startsWith("{") && body.endsWith("}"),
                    "Body should be valid JSON object. Body: " + body);
        }
    }
}