package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 E2E 娴嬭瘯锛氭帴鍙ｇ户鎵裤€佸崰浣嶇楂樼骇銆佹潯浠堕檺瀹氥€?
 * <p>
 * 涓?ProxyE2eTest 鍏变韩 Spring 涓婁笅鏂囥€?
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyP2E2eTest {

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

    // ==================== 澶氱骇鎺ュ彛缁ф壙 + CGLIB 浠ｇ悊 ====================

    @Test
    void postRootSave_withInheritedInterface_resolvesRequestBodyAndParam() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-inherit/root-save?id=abc")
                .post(RequestBody.create(JSON, "\"data\""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            // @RequestBody 鎺ユ敹 JSON 瀛楃涓插甫寮曞彿
            assertTrue(body.contains("data") && body.contains("abc"),
                    "Body should contain both data and abc: " + body);
        }
    }

    @Test
    void getMiddleQuery_withInheritedInterface_resolvesRequestParam() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-inherit/middle-query?name=inherited")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-inherited", resp.body().string());
        }
    }

    // ==================== 鍗犱綅绗?+ 閫氶厤绗﹁矾寰?====================

    @Test
    void getPlaceholderWithPathVar_resolvesBothPlaceholderAndPathVariable() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy/placeholder-resolved/42")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("wildcard-42", resp.body().string());
        }
    }

    // ==================== 澶氭鍗犱綅绗?====================

    @Test
    void getMultiPlaceholder_resolvesAllSegments() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy/placeholder-resolved/multi/detail?q=abc")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-abc", resp.body().string());
        }
    }

    // ==================== @RequestMapping params 鏉′欢闄愬畾 ====================

    @Test
    void getGreet_withLangParam_routesToCorrectMethod() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond/greet?lang=en")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-lang", resp.body().string());
        }
    }

    @Test
    void getGreet_withoutLangParam_routesToDefaultMethod() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/proxy-cond/greet")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-default", resp.body().string());
        }
    }
}