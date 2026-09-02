package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态资源安全 E2E 测试。
 * <p>覆盖正常访问、目录遍历攻击防护、不存在的资源、路径解析边界。</p>
 */
public class StaticResourceSecurityTest extends BaseE2ETest {

    private String base() {
        return url("/api");
    }

    // ======================== 正常回归 (200) ========================

    @Test
    void staticResource_shouldSucceed() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/test.txt")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello, Static Resource!"));
        }
    }

    @Test
    void subdirectoryResource_shouldSucceed() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/sub/index.html")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Welcome"));
        }
    }

    // ======================== 目录遍历防护 (404) ========================

    /** 基本 "../" 遍历 — 从 static 目录向上跳转到根 classpath */
    @Test
    void pathTraversal_basicDotDot_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/../test.txt")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    /** 深层 "../../" 遍历 — 尝试跳出 classpath 根目录 */
    @Test
    void pathTraversal_deepDotDot_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/../../etc/passwd")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    /**
     * 深层 "../../../" 遍历 — 跳过 static/ 后持续向上，目标文件不在 classpath 根。
     * <p>注意: OkHttp 客户端会规范化路径中的 "../" 段，实际到达服务器的路径已经失去了 "../" 信息。
     * 此测试验证的是规范化后的路径（跳出 /static/ 范围后）不会被错误服务。
     * 真正验证服务端 ".." 过滤逻辑的请见 URL 编码测试。</p>
     */
    @Test
    void pathTraversal_deepMultipleLevels_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/../../../etc/passwd")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    /** 复杂编码绕过尝试: 混用 .. 和 . */
    @Test
    void pathTraversal_mixedDots_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/....//....//etc/passwd")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    /** URL 编码的 ".." (%2e%2e) 绕过尝试 */
    @Test
    void pathTraversal_urlEncodedDotDot_shouldReturn404() throws Exception {
        // 使用 java.net.URL 避免 OkHttp 对路径二次编码
        Request req = new Request.Builder()
                .url(new URL(url("/api/static/%2e%2e/test.txt")))
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    /** URL 编码的 "../" (%2e%2e%2f) 绕过 */
    @Test
    void pathTraversal_fullEncoded_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(new URL(url("/api/static/%2e%2e%2ftest.txt")))
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    /** 双 URL 编码 (%252e%252e) 绕过 — 解码两次后变成 ".." */
    @Test
    void pathTraversal_doubleEncoded_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(new URL(url("/api/static/%252e%252e/test.txt")))
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ======================== 不存在资源 (404) ========================

    @Test
    void nonexistentResource_shouldReturn404() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/nonexistent.txt")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ======================== HTTP 方法限制（对齐 Spring MVC） ========================

    /** 静态资源仅支持 GET/HEAD（Spring MVC ResourceHttpRequestHandler 语义），POST 应 405 */
    @Test
    void staticResource_withPost_shouldReturn405() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/test.txt")
                .post(okhttp3.RequestBody.create("", okhttp3.MediaType.parse("text/plain; charset=utf-8")))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "静态资源 POST 应返回 405 Method Not Allowed");
        }
    }

    /** HEAD 应自动映射到 GET（RFC 7231 §4.3.2），返回 200 且无 body */
    @Test
    void staticResource_withHead_shouldReturn200NoBody() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/test.txt")
                .head()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "静态资源 HEAD 应自动映射到 GET 返回 200");
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "HEAD 不应有 body，实际: " + body);
            assertNotNull(resp.header("Content-Length"));
        }
    }

    /** DELETE 静态资源同样应 405 */
    @Test
    void staticResource_withDelete_shouldReturn405() throws Exception {
        Request req = new Request.Builder()
                .url(base() + "/static/test.txt")
                .delete()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "静态资源 DELETE 应返回 405");
        }
    }
}