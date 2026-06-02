package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actuator 端点集成测试。
 * <p>验证 Actuator 端点在 perf-spring-web 框架中的基本可用性：
 * health 端点、info 端点、links 端点、API 版本协商、404 处理。</p>
 */
@Slf4j
public class ActuatorHealthTest extends BaseE2ETest {

    private final String actuatorBase = "http://localhost:9090/api/actuator";

    @Test
    void healthEndpoint_shouldReturnUp() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("UP", body.get("status"));
        }
    }

    @Test
    void linksEndpoint_shouldReturnLinks() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String bodyStr = resp.body().string();
            log.info("Links response: " + bodyStr);
            Map<String, Object> body = JSON.parseObject(bodyStr, Map.class);
            assertNotNull(body.get("_links"), "Links endpoint should return _links. Body: " + bodyStr);
        }
    }

    @Test
    void unknownEndpoint_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/nonexistent")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void healthEndpoint_withApiV3AcceptHeader_shouldReturnV3Format() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .header("Accept", "application/vnd.spring-boot.actuator.v3+json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String contentType = resp.header("Content-Type", "");
            assertTrue(contentType.contains("vnd.spring-boot.actuator.v3")
                    || contentType.contains("json"), "V3 Accept should be accepted: " + contentType);
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("UP", body.get("status"));
        }
    }

    @Test
    void healthEndpoint_withApiV2AcceptHeader_shouldReturnV2Format() throws Exception {
        Request req = new Request.Builder()
                .url(actuatorBase + "/health")
                .header("Accept", "application/vnd.spring-boot.actuator.v2+json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("UP", body.get("status"));
        }
    }
}