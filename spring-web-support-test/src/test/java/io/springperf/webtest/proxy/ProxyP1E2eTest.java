package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 E2E 娴嬭瘯锛欳GLIB 浠ｇ悊 Controller 鐨勫弬鏁拌竟鐣屽満鏅€?
 * <p>
 * 涓?ProxyE2eTest 鍏变韩鍚屼竴涓?Spring 涓婁笅鏂囷紙鐩稿悓閰嶇疆锛夛紝
 * 浣嗗崟鐙粍缁囨祴璇曠被浠ヤ繚鎸佸彲缁存姢鎬с€?
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyP1E2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");


    @LocalServerPort
    private int serverPort;

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
    private String baseUrl() {
        return url("/api");
    }

    // ==================== 澶氬弬鏁扮粍鍚堬細@RequestBody + @PathVariable + @RequestParam + @RequestHeader + optional + defaultValue ====================

    @Test
    void postMixedParams_withProxy_resolvesAllAnnotations() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/mixed/abc?key=k1&def=custom")
                .post(RequestBody.create(JSON, "\"req-body\""))
                .addHeader("X-Custom", "hdr-val")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            // body|id|key|header|optional|withDefault
            assertTrue(body.contains("req-body") && body.contains("abc") && body.contains("k1"),
                    "Should contain body, path variable and request param: " + body);
            assertTrue(body.contains("hdr-val"),
                    "Should contain header value: " + body);
            assertTrue(body.contains("custom"),
                    "Should use provided defaultValue param: " + body);
        }
    }

    @Test
    void postMixedParams_withProxy_usesDefaultValue() throws Exception {
        // 涓嶄紶 def 鍙傛暟锛岄獙璇?defaultValue="fallback" 鐢熸晥
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/mixed/xyz?key=k2")
                .post(RequestBody.create(JSON, "\"data\""))
                .addHeader("X-Custom", "hdr")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("data") && body.contains("xyz") && body.contains("k2"),
                    "Should contain body, path variable and request param: " + body);
            assertTrue(body.contains("null|fallback"),
                    "Optional should be null, default should be 'fallback': " + body);
        }
    }

    @Test
    void postMixedParams_withoutRequiredParam_returns400() throws Exception {
        // 缂哄皯 required @RequestParam("key")
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/mixed/abc")
                .post(RequestBody.create(JSON, "\"body\""))
                .addHeader("X-Custom", "hdr")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertTrue(resp.code() >= 400,
                    "Missing required @RequestParam should return 4xx, got " + resp.code());
        }
    }

    // ==================== @RequestBody 绌?body ====================

    @Test
    void postEmptyBody_withProxy_returns400() throws Exception {
        // Content-Length 涓?0 鐨?POST + @RequestBody(required=true)锛?
        // 瀵归綈 Spring 璇箟鎶?400锛圧equestBodyResolver: readBody 绌?body 杩斿洖 null
        // 鈫?required 缂哄け 鈫?HttpMessageNotReadableException锛夈€傝 RequestBodyResolverTest銆?
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/empty-body")
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
            String body = resp.body().string();
            assertNotNull(body);
        }
    }

    @Test
    void postEmptyBodyOptional_withProxy_returnsGotNull() throws Exception {
        // @RequestBody(required=false)锛氱┖ body 涓嶆姏 400锛宐ody 瑙ｆ瀽涓?null 鈫?"got:null"
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/empty-body-optional")
                .post(RequestBody.create(new byte[0]))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("got:null"),
                    "required=false empty body should bind null: " + body);
        }
    }

    // ==================== @RequestHeader 澶氬€笺€佸彲閫夈€佺己澶?====================

    @Test
    void getMultiHeader_withProxy_resolvesList() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/headers")
                .get()
                .addHeader("X-Multi", "val1")
                .addHeader("X-Multi", "val2")
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("val1") && body.contains("val2"),
                    "Should contain both header values: " + body);
        }
    }

    @Test
    void getRequiredHeader_withProxy_missing_returns400() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/required-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertTrue(resp.code() >= 400,
                    "Missing required @RequestHeader should return 4xx, got " + resp.code());
        }
    }

    // ==================== ResponseEntity 杩斿洖 + proxy ====================

    @Test
    void getResponseEntity_withProxy_returnsCustomStatusAndHeaders() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-p1/entity")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            assertEquals("header-value", resp.header("X-Custom-Resp"));
            String body = resp.body().string();
            assertEquals("entity-body", body);
        }
    }
}