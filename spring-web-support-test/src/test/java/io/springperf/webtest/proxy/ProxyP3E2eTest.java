package io.springperf.webtest.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 E2E 娴嬭瘯锛欳ontroller 绫荤户鎵裤€丂RequestMapping 璐熷悜鏉′欢銆丂ExceptionHandler 鐖跺瓙璺敱銆丗ilter 闃绘柇銆?
 * <p>
 * 涓?ProxyE2eTest 鍏变韩 Spring 涓婁笅鏂囷紙绔彛 9092锛夈€?
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyP3E2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML_TYPE = MediaType.parse("application/xml; charset=utf-8");

    private static final ObjectMapper MAPPER = new ObjectMapper();


    @LocalServerPort
    private int serverPort;

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
    private String baseUrl() {
        return url("/api");
    }

    // ==================== 1. Controller 绫荤户鎵?(Child extends Parent) ====================

    @Test
    void childController_parentGreet_stillWorks() throws Exception {
        // ChildController 缁ф壙鑷?ParentController锛屼笉瑕嗗啓 greet 鏂规硶
        // getDeclaredMethods 鍙繑鍥炴湰绫诲０鏄庣殑鏂规硶锛屽洜姝?greet 鐢?ParentController bean 娉ㄥ唽
        // `/proxy-parent/greet` 璺緞閫氳繃 ParentController 澶勭悊
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-parent/greet?name=test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-test", resp.body().string());
        }
    }

    @Test
    void childController_ownMethod_returnsStatus() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-parent/status")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("child-ok", resp.body().string());
        }
    }

    // ==================== 2. @RequestMapping 璐熷悜鏉′欢 (headers/params/consumes) ====================

    @Test
    void negativeCond_withoutBlockHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/no-block-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void negativeCond_withBlockHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/no-block-header")
                .header("X-Block", "true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void negativeCond_withoutSkipParam_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/no-skip-param")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void negativeCond_withSkipParam_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/no-skip-param?skip=true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void negativeCond_notXmlConsumes_withJson_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/not-xml")
                .post(RequestBody.create("{}", JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void negativeCond_notXmlConsumes_withXml_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/not-xml")
                .post(RequestBody.create("<r/>", XML_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // consumes = "!application/xml" rejects XML 鈫?no route matched
            assertEquals(404, resp.code());
        }
    }

    @Test
    void positiveHeaderCondition_withRequiredHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/with-header")
                .header("X-Required", "present")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void positiveHeaderCondition_withoutRequiredHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond-extra/with-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 3. @ExceptionHandler 鐖跺瓙寮傚父璺敱 (with proxy) ====================

    @Test
    void childController_parentException_caughtByParentHandler() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-parent/parent-exception")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("parent", body.get("handler"));
            assertTrue(((String) body.get("error")).contains("parent error"));
        }
    }

    @Test
    void childController_childException_caughtBySpecificHandler() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-parent/child-exception")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("child", body.get("handler"));
            assertTrue(((String) body.get("error")).contains("child error"));
        }
    }

    // ==================== 4. WebFilter 闃绘柇璇锋眰 (with proxy) ====================

    @Test
    void blockingFilter_withProxy_returns403() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-parent/blocked")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(403, resp.code());
            assertEquals("blocked", resp.header("X-Blocking-Filter"));
            String body = resp.body().string();
            assertTrue(body.contains("blocked by proxy filter"));
        }
    }
}
