package io.springperf.webtest;

import io.springperf.webtest.interceptor.LifecycleInterceptor;
import okhttp3.*;
import okio.BufferedSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1 E2E 测试：重要功能场景覆盖。
 */
public class CoreFeaturesP1Test extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(CoreFeaturesP1Test.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.parse("multipart/form-data");

    private String coreUrl() {
        return url("/api/core");
    }

    private String p1Url() {
        return url("/api/p1");
    }

    private String demoUrl() {
        return url("/api/demo");
    }

    private String baseUrl() {
        return url("/api");
    }

    @BeforeEach
    void resetInterceptorCounts() {
        LifecycleInterceptor.resetCounts();
    }

    // ==================== 1. PATCH 方法 ====================

    @Test
    void patchMethod_returns200() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/patch-test").method("PATCH", RequestBody.create("", JSON))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("patch-ok", resp.body().string());
        }
    }

    // ==================== 2. @ResponseStatus 201/202 ====================

    @Test
    void responseStatus201_returnsCreated() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/status-201").post(RequestBody.create("", JSON)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
            String body = resp.body().string();
            assertEquals(201, com.alibaba.fastjson2.JSON.parseObject(body).getIntValue("status"),
                    "@ResponseStatus 端点应返回 {\"status\":201}，实际: " + body);
        }
    }

    @Test
    void responseStatus202_returnsAccepted() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/status-202").put(RequestBody.create("", JSON)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(202, resp.code());
            String body = resp.body().string();
            assertEquals(202, com.alibaba.fastjson2.JSON.parseObject(body).getIntValue("status"),
                    "@ResponseStatus 端点应返回 {\"status\":202}，实际: " + body);
        }
    }

    // ==================== 3. TypeMismatchException ====================

    @Test
    void typeMismatch_withNonNumericParam_returns400() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/type-mismatch?id=abc").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // int 参数收到非数字字符串应触发 MethodArgumentTypeMismatch → 400
            assertEquals(400, resp.code(), "Type mismatch should return 400, got " + resp.code());
        }
    }

    // ==================== 4. @RequestPart 多个文件 ====================

    @Test
    void multiPart_withTwoFiles_returnsBothFilenames() throws Exception {
        RequestBody multipartBody = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("file1", "f1.txt", RequestBody.create("content1", MediaType.parse("text/plain")))
                .addFormDataPart("file2", "f2.txt", RequestBody.create("content2", MediaType.parse("text/plain")))
                .build();
        Request req = new Request.Builder().url(p1Url() + "/multi-part").post(multipartBody).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("f1.txt-f2.txt", resp.body().string());
        }
    }

    // ==================== 5. @CrossOrigin 类级别 ====================

    @Test
    void corsClassLevel_preflight_returnsAllowOrigin() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/p1/cors-class/test")
                .header("Origin", "http://class-level.example.com").header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", null).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "类级 @CrossOrigin 预检应返回确定状态码，实际: " + resp.code());
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertEquals("http://class-level.example.com", allowOrigin,
                    "类级 @CrossOrigin 应精确回显允许的来源，实际: " + resp.headers());
            assertNotNull(resp.header("Access-Control-Allow-Methods"), "预检应返回 Allow-Methods");
        }
    }

    @Test
    void corsClassLevel_actualRequest_returnsAllowOrigin() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/p1/cors-class/test")
                .header("Origin", "http://class-level.example.com").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String allowOrigin = resp.header("Access-Control-Allow-Origin");
            assertEquals("http://class-level.example.com", allowOrigin,
                    "类级 @CrossOrigin 实际请求应精确回显来源，实际: " + resp.headers());
        }
    }

    // ==================== 6. SseJsonEmitter ====================

    @Test
    void sseJsonEmitter_receivesSseEvents() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/sse-json").get().build();
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> data = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger();
        CLIENT.newCall(req).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, java.io.IOException e) {
                failure.set(e);
                done.countDown();
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) {
                try (Response resp = response) {
                    status.set(resp.code());
                    BufferedSource source = resp.body().source();
                    while (!source.exhausted()) {
                        String line = source.readUtf8Line();
                        if (line != null && line.startsWith("data:")) {
                            data.set(line.substring(5).trim());
                            break;
                        }
                    }
                } catch (Throwable t) {
                    failure.set(t);
                } finally {
                    done.countDown();
                }
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "SSE 事件应在超时前收到");
        assertNull(failure.get(), "SSE 请求不得失败: " + failure.get());
        assertEquals(200, status.get());
        assertNotNull(data.get(), "应收到 SSE data 帧");
        assertEquals("hello", com.alibaba.fastjson2.JSON.parseObject(data.get()).getString("msg"),
                "SSE data 应为 {\"msg\":\"hello\"}，实际: " + data.get());
    }

    // ==================== 7. @Optimize 启用 ====================

    @Test
    void optimizeEndpoint_returns200() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/optimize-check").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals(true, com.alibaba.fastjson2.JSON.parseObject(body).get("optimized"),
                    "@Optimize 端点应返回 {\"optimized\":true}，实际: " + body);
        }
    }

    // ==================== 8. 文件下载 Content-Disposition ====================

    @Test
    void resourceDownload_returnsContent() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/download-resource").get().build();
        // Resource/FileReturnValueResolver 均设置 Content-Length + 结束帧，OkHttp 可完整读取，
        // 超时即视为实现缺陷，不被豁免。
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("Hello, Static Resource!", body.trim(), "资源下载应返回 test.txt 完整内容，实际: " + body);
        }
    }

    @Test
    void fileDownload_returnsContent() throws Exception {
        Request req = new Request.Builder().url(p1Url() + "/download-file").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            // 端点返回模块根目录的 pom.xml：逐字节比对，任意非空响应（含错误页）都不再能通过
            assertArrayEquals(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("pom.xml")), resp.body().bytes());
        }
    }

    // ==================== 9. Interceptor 生命周期计数 ====================

    @Test
    void interceptorLifecycle_countsTrackedCorrectly() throws Exception {
        LifecycleInterceptor.resetCounts();
        assertEquals(0, LifecycleInterceptor.postHandleCount);
        assertEquals(0, LifecycleInterceptor.afterCompletionCount);

        Request req = new Request.Builder().url(baseUrl() + "/core/lifecycle/check").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }

        assertTrue(LifecycleInterceptor.postHandleCount >= 1,
                "postHandle should be called at least once: " + LifecycleInterceptor.postHandleCount);
        assertTrue(LifecycleInterceptor.afterCompletionCount >= 1,
                "afterCompletion should be called at least once: " + LifecycleInterceptor.afterCompletionCount);
    }

    // ==================== 10. 404 场景 ====================

    @Test
    void nonexistentPath_returns404() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/nonexistent-route-xyz").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 11. 405 Method Not Allowed ====================

    @Test
    void wrongMethod_returns405() throws Exception {
        Request req = new Request.Builder().url(coreUrl() + "/bytes").post(RequestBody.create("", JSON)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code());
        }
    }

    // ==================== 12. 重复 Mapping 不崩溃 ====================

    @Test
    void duplicateMapping_shouldNotCrash() throws Exception {
        // 两个 @GetMapping("/duplicate") 方法，框架应正常响应其中一个
        Request req = new Request.Builder().url(p1Url() + "/duplicate").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.matches("duplicate-(first|second)"),
                    "应命中两个重复 mapping 中的某一个方法，实际: " + body);
        }
    }
}
