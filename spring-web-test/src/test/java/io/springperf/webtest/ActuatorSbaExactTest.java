package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 精确模拟：无 context-path，独立管理端口，SBA v2 Accept 头。 验证所有 actuator 端点可被 SBA 2.3.0.1 正常访问。
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.endpoints.web.exposure.include=*" })
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

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).build();

    private String mgmtBase() {
        return "http://localhost:" + managementServer.getActualPort() + "/actuator";
    }

    @ParameterizedTest
    // 注：不含 /caches —— Boot 4 把 CachesEndpoint 从 spring-boot-actuator 移出（仅在有 cache
    // 实现时由对应模块提供），本项目未引入任何 cache 依赖，故该端点在 4.x 下本就不存在。
    @ValueSource(strings = { "", "/health", "/mappings", "/beans", "/env", "/metrics", "/conditions", "/configprops",
            "/loggers", "/threaddump", "/scheduledtasks", "/info" })
    void sbaEndpoint_shouldSucceed(String endpoint) throws Exception {
        Request req = new Request.Builder().url(mgmtBase() + endpoint)
                .header("Accept", "application/vnd.spring-boot.actuator.v2+json").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            String body = resp.body() != null ? resp.body().string() : "<empty>";
            String contentType = resp.header("Content-Type", "");
            log.info("GET {} -> {} bodyLength={} contentType={}", endpoint, resp.code(), body.length(), contentType);
            assertEquals(200, resp.code(), "SBA v2 GET " + endpoint + " should return 200. Body: " + body);
            assertEquals("application/vnd.spring-boot.actuator.v2+json", contentType.split(";")[0].trim(),
                    "SBA v2 Accept 应产出 v2 media type，实际: " + contentType);
            // 首尾字符判断无法区分真实端点响应与错误 JSON，按端点校验各自的结构字段
            JSONObject json = JSON.parseObject(body);
            assertNotNull(json, "SBA 端点必须返回合法 JSON 对象. Body: " + body);
            assertFalse(json.containsKey("error"), "SBA 端点不得返回错误 JSON. Body: " + body);
            switch (endpoint) {
                case "" -> {
                    JSONObject links = json.getJSONObject("_links");
                    assertNotNull(links, "links 端点应返回 _links. Body: " + body);
                    assertFalse(links.isEmpty(), "links 端点应返回可用链接. Body: " + body);
                }
                case "/health" -> assertEquals("UP", json.getString("status"), "Body: " + body);
                case "/metrics" -> assertNotNull(json.get("names"), "Body: " + body);
                case "/env" -> assertNotNull(json.get("activeProfiles"), "Body: " + body);
                case "/beans", "/mappings", "/configprops", "/conditions" ->
                        assertNotNull(json.get("contexts"), "Body: " + body);
                case "/loggers" -> assertNotNull(json.get("levels"), "Body: " + body);
                case "/threaddump" -> assertNotNull(json.get("threads"), "Body: " + body);
                case "/scheduledtasks" -> assertNotNull(json.get("cron"), "Body: " + body);
                case "/caches" -> assertNotNull(json.get("cacheManagers"), "Body: " + body);
                case "/info" -> {
                    // info 无配置时允许返回空对象，但必须是合法 JSON 且不含 error（上面已校验）
                }
                default -> assertFalse(json.isEmpty(), "端点应返回非空 JSON 对象. Body: " + body);
            }
        }
    }
}
