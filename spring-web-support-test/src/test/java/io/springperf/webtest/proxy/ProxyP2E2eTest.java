package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 E2E 测试：接口继承、占位符高级、条件限定。
 * <p>
 * 与 ProxyE2eTest 共享 Spring 上下文。
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=9092",
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

    private final String baseUrl = "http://localhost:9092/api";

    // ==================== 多级接口继承 + CGLIB 代理 ====================

    @Test
    void postRootSave_withInheritedInterface_resolvesRequestBodyAndParam() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-inherit/root-save?id=abc")
                .post(RequestBody.create(JSON, "\"data\""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            // @RequestBody 接收 JSON 字符串带引号
            assertTrue(body.contains("data") && body.contains("abc"),
                    "Body should contain both data and abc: " + body);
        }
    }

    @Test
    void getMiddleQuery_withInheritedInterface_resolvesRequestParam() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-inherit/middle-query?name=inherited")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-inherited", resp.body().string());
        }
    }

    // ==================== 占位符 + 通配符路径 ====================

    @Test
    void getPlaceholderWithPathVar_resolvesBothPlaceholderAndPathVariable() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy/placeholder-resolved/42")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("wildcard-42", resp.body().string());
        }
    }

    // ==================== 多段占位符 ====================

    @Test
    void getMultiPlaceholder_resolvesAllSegments() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy/placeholder-resolved/multi/detail?q=abc")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("multi-abc", resp.body().string());
        }
    }

    // ==================== @RequestMapping params 条件限定 ====================

    @Test
    void getGreet_withLangParam_routesToCorrectMethod() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond/greet?lang=en")
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
                .url(baseUrl + "/proxy-cond/greet")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-default", resp.body().string());
        }
    }
}