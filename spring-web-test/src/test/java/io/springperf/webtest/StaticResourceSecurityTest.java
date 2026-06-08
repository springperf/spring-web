package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态资源安全 E2E 测试。
 * <p>覆盖正常访问、目录遍历攻击防护、不存在的资源、路径解析边界。</p>
 */
public class StaticResourceSecurityTest extends BaseE2ETest {

    private static final String BASE = "http://localhost:9090/api";

    // ======================== 正常回归 (200) ========================

    @Test
    void staticResource_shouldSucceed() throws Exception {
        Request req = new Request.Builder()
                .url(BASE + "/static/test.txt")
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
                .url(BASE + "/static/sub/index.html")
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
                .url(BASE + "/static/../test.txt")
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
                .url(BASE + "/static/../../etc/passwd")
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
                .url(BASE + "/static/../../../etc/passwd")
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
                .url(BASE + "/static/....//....//etc/passwd")
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
                .url(new URL("http://localhost:9090/api/static/%2e%2e/test.txt"))
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
                .url(new URL("http://localhost:9090/api/static/%2e%2e%2ftest.txt"))
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
                .url(new URL("http://localhost:9090/api/static/%252e%252e/test.txt"))
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
                .url(BASE + "/static/nonexistent.txt")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }
}