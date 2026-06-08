package io.springperf.webtest.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 {@code @RequestMapping} 的 headers/params/consumes/produces 条件中
 * 使用 {@code ${...}} 占位符能正确解析。
 * <p>
 * 使用独立端口 9094，与共享的 proxy 上下文隔离。
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=9094",
                "server.servlet.context-path=/api",
                "test.header.cond=X-Custom=present",
                "test.param.cond=required-param",
                "test.consumes.cond=application/json",
                "test.produces.cond=application/json"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class PlaceholderCondE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML_TYPE = MediaType.parse("application/xml; charset=utf-8");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl = "http://localhost:9094/api";

    // ==================== headers 占位符 ====================

    @Test
    void headerPlaceholder_withMatchingHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/header-check")
                .header("X-Custom", "present")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("header", body.get("matched"));
        }
    }

    @Test
    void headerPlaceholder_withoutMatchingHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/header-check")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== params 占位符 ====================

    @Test
    void paramPlaceholder_withMatchingParam_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/param-check?required-param=any")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("param", body.get("matched"));
        }
    }

    @Test
    void paramPlaceholder_withoutMatchingParam_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/param-check")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== consumes 占位符 ====================

    @Test
    void consumesPlaceholder_withJsonContentType_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/consume-check")
                .post(RequestBody.create("{}", JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("consume", body.get("matched"));
        }
    }

    @Test
    void consumesPlaceholder_withXmlContentType_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/consume-check")
                .post(RequestBody.create("<r/>", XML_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== produces 占位符 ====================

    @Test
    void producesPlaceholder_withJsonAccept_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/produce-check")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("produce", body.get("matched"));
        }
    }

    @Test
    void producesPlaceholder_withXmlAccept_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/placeholder-cond/produce-check")
                .header("Accept", "text/xml")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }
}