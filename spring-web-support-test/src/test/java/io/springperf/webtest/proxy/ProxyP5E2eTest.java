package io.springperf.webtest.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P5 E2E 娴嬭瘯锛欻ttpEntity 鍙傛暟銆丆allable 寮傛銆乥yte[]/Resource 杩斿洖鍊笺€?
 * 澶氳矾寰勬槧灏勩€佸鏂规硶鏄犲皠銆丷esponseStatusException銆丷equestEntity 鍙傛暟銆?
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyP5E2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private static final ObjectMapper MAPPER = new ObjectMapper();


    @LocalServerPort
    private int serverPort;

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
    private String baseUrl() {
        return url("/api");
    }

    // ==================== 1. HttpEntity 鍙傛暟 ====================

    @Test
    void httpEntityParam_receivesRequestBodyAndHeaders() throws Exception {
        String jsonBody = "{\"hello\":\"world\"}";
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/entity-body")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertNotNull(body.get("body"));
            assertTrue(body.get("contentType").toString().contains("application/json"));
        }
    }

    // ==================== 2. Callable 寮傛杩斿洖 ====================

    @Test
    void callableReturn_asyncExecution_returnsDone() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/callable")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("callable-done", resp.body().string());
        }
    }

    // ==================== 3. byte[] 杩斿洖鍊?====================

    @Test
    void byteArrayReturn_returnsBytes() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/bytes")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("byte-data", resp.body().string());
        }
    }

    // ==================== 4. Resource 杩斿洖鍊?====================

    @Test
    void resourceReturn_returnsContent() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/resource")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("resource-content", resp.body().string());
        }
    }

    // ==================== 5. 澶氳矾寰勬槧灏?====================

    @Test
    void multiPath_accessPathA_returnsOk() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/multi-path-a")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("multi-path", body.get("matched"));
        }
    }

    @Test
    void multiPath_accessPathB_returnsOk() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/multi-path-b")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("multi-path", body.get("matched"));
        }
    }

    // ==================== 6. 澶氭柟娉曟槧灏?====================

    @Test
    void multiMethod_getRequest_returnsOk() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/multi-method")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("multi-method", body.get("matched"));
        }
    }

    @Test
    void multiMethod_postRequest_returnsOk() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/multi-method")
                .post(RequestBody.create("{}", JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = MAPPER.readValue(resp.body().string(), Map.class);
            assertEquals("multi-method", body.get("matched"));
        }
    }

    // ==================== 7. ResponseStatusException ====================

    @Test
    void responseStatusException_returns410() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/gone")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(410, resp.code(),
                    "ResponseStatusException(HttpStatus.GONE) should map to 410");
        }
    }

    // ==================== 8. RequestEntity 鍙傛暟 ====================

    @Test
    void requestEntityParam_receivesMethodAndBody() throws Exception {
        String jsonBody = "\"test-data\"";
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p5/request-entity")
                .post(RequestBody.create(jsonBody, JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("POST", body.get("method"));
            assertNotNull(body.get("body"));
        }
    }
}