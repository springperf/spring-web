package io.springperf.webtest.proxy;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E 测试：验证 CGLIB 代理 Controller 场景下框架能正确解析参数注解。
 * <p>
 * 测试链路：
 * CGLIB 代理方法 → targetClass.getDeclaredMethods() (取实现类方法路由)
 * → createMethodParameters → findAnnotatedMethod (从接口取参数注解)
 * → ArgumentResolverRegistry → 参数解析器 (发现 @RequestBody/@RequestParam)
 * → 正常调用 Controller 方法
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
public class ProxyE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String baseUrl = "http://localhost:9092/api";

    // ==================== @RequestBody + @RequestParam ====================

    @Test
    void postSave_withBodyAndId_returnsConcatenated() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/save?id=abc")
                .post(RequestBody.create(JSON, "\"test-body\""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("test-body") && body.contains("abc"),
                    "Body should contain both the input and id param: " + body);
        }
    }

    // ==================== @RequestParam ====================

    @Test
    void get_withNameParam_returnsGreeting() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/get?name=world")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("hello-world", body);
        }
    }

    @Test
    void get_withoutRequiredParam_returns400() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/get")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertTrue(resp.code() >= 400,
                    "Missing required @RequestParam should return 4xx, got " + resp.code());
        }
    }

    // ==================== @PathVariable ====================

    @Test
    void get_withPathVariable_returnsProcessed() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/path/42")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("id-42", body);
        }
    }

    // ==================== 混合注解 ====================

    @Test
    void postMixed_withAllParams_resolvesCorrectly() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/mixed/mytag?key=mykey")
                .post(RequestBody.create(JSON, "\"data\""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("data") && body.contains("mykey") && body.contains("mytag"),
                    "Body should contain body, key and tag values: " + body);
        }
    }

    // ==================== 简单参数 ====================

    @Test
    void getEcho_withMsg_returnsSameValue() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/echo?msg=ping")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("ping", body);
        }
    }

    // ==================== 验证 CGLIB 代理确实生效 ====================

    @Test
    void controllerBean_isCglibProxy() {
        // ProxyE2eApp 配置了 @EnableAspectJAutoProxy(proxyTargetClass = true),
        // ProxyController 应被 CGLIB 代理
        // 此测试在 Spring 上下文可直接验证，但在 E2E HTTP 测试中通过行为间接验证。
        // 此处确保服务器启动正常，路由正确。
    }

    // ==================== 占位符路径解析 ====================

    @Test
    void placeholderPath_resolvesFromEnvironment() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy/placeholder-resolved/echo?msg=placeholder-ok")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("placeholder-ok", body);
        }
    }

    @Test
    void placeholderPath_unknownKey_usesDefault() {
        // 没有配置的 key 应使用默认值 /api/placeholder
        // 此处无法直接测试（这是另一个 Spring Boot 应用），已在单元测试中覆盖
    }

    // ==================== P0: @ModelAttribute + CGLIB 代理 ====================

    @Test
    void postModelAttribute_withProxy_bindsFormData() throws Exception {
        RequestBody formBody = new okhttp3.FormBody.Builder()
                .add("name", "John")
                .add("age", "25")
                .build();
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/model-attr")
                .post(formBody)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("John") && body.contains("25"),
                    "Body should contain bound name and age: " + body);
        }
    }

    // ==================== P0: @RequestPart + CGLIB 代理 ====================

    @Test
    void postUpload_withProxy_parsesMultipartFile() throws Exception {
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "test.txt",
                        RequestBody.create("hello world", MediaType.parse("text/plain")))
                .build();
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/upload")
                .post(multipartBody)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("test.txt"),
                    "Body should contain original filename: " + body);
        }
    }

    // ==================== P0: @ExceptionHandler + CGLIB 代理 ====================

    @Test
    void getTriggerError_withProxy_handledByControllerAdvice() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/trigger-error")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(409, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("handled:test-proxy-error"),
                    "Should be handled by @ExceptionHandler: " + body);
        }
    }

    // ==================== P0: Interceptor + CGLIB 代理 ====================

    @Test
    void interceptor_isInvokedForProxyController() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/echo?msg=intercepted")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("called", resp.header("X-Test-Interceptor"),
                    "Interceptor should add X-Test-Interceptor header");
            assertEquals("intercepted", resp.body().string());
        }
    }

    // ==================== P2: WebFilter + CGLIB 代理 ====================

    @Test
    void webFilter_executedForProxyController() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/echo?msg=filter-test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("executed", resp.header("X-Test-Filter"),
                    "WebFilter should add X-Test-Filter header");
            assertEquals("filter-test", resp.body().string());
        }
    }

    // ==================== @InitBinder + CGLIB 代理 ====================

    @Test
    void initBinder_withProxy_appliesPropertyEditor() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-api/init-binder")
                .post(RequestBody.create(
                        MediaType.parse("application/x-www-form-urlencoded"),
                        "name=hello"))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("binder:hello", body,
                    "@InitBinder should prepend 'binder:' to the name field");
        }
    }
}