package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态资源深度语义 E2E：条件请求（ETag/If-None-Match → 304、Last-Modified/If-Modified-Since → 304）、 HEAD 元数据（Content-Length 保留、无
 * body）、目录 welcome page（index.html）、 路径穿越防护（../ → 404）、gzip 预压缩变体（*.gz + Accept-Encoding → Content-Encoding: gzip）。
 */
@SpringBootTest(classes = ConfigAlignTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/", "spring.web.resources.add-mappings=true" })
class StaticResourceDeepE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private okhttp3.Response get(String path, String... headers) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url(path));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(builder.build()).execute();
    }

    // ==================== 条件请求 ====================

    @Test
    void etag_issued_andIfNoneMatch_returns304() throws Exception {
        String etag;
        okhttp3.Response first = get("/e2e-note.txt");
        try {
            assertEquals(200, first.code());
            etag = first.header("ETag");
            assertNotNull(etag, "静态资源响应应携带 ETag");
        } finally {
            first.close();
        }

        okhttp3.Response second = get("/e2e-note.txt", "If-None-Match", etag);
        try {
            assertEquals(304, second.code(), "If-None-Match 命中应 304");
        } finally {
            second.close();
        }
    }

    @Test
    void lastModified_andIfModifiedSince_returns304() throws Exception {
        String lastModified;
        okhttp3.Response first = get("/e2e-note.txt");
        try {
            assertEquals(200, first.code());
            lastModified = first.header("Last-Modified");
            assertNotNull(lastModified, "静态资源响应应携带 Last-Modified");
        } finally {
            first.close();
        }

        okhttp3.Response second = get("/e2e-note.txt", "If-Modified-Since", lastModified);
        try {
            assertEquals(304, second.code(), "If-Modified-Since 未修改应 304");
        } finally {
            second.close();
        }
    }

    // ==================== HEAD ====================

    @Test
    void head_returnsMetadataOnly_withoutBody() throws Exception {
        // 显式 identity：禁用 OkHttp 透明 gzip（否则它自动加 Accept-Encoding: gzip，
        // 使 GET 被解压而 HEAD 只剩压缩后长度的 Content-Length，二者不可比）
        long fullLength;
        okhttp3.Response full = get("/e2e-note.txt", "Accept-Encoding", "identity");
        try {
            assertEquals(200, full.code());
            fullLength = full.body().bytes().length;
            assertTrue(fullLength > 0, "资源应有内容");
        } finally {
            full.close();
        }

        okhttp3.Response head = call(CLIENT, new okhttp3.Request.Builder().url(url("/e2e-note.txt"))
                .header("Accept-Encoding", "identity").head().build());
        try {
            assertEquals(200, head.code(), "HEAD 应 200");
            assertEquals(0, head.body().bytes().length, "HEAD 不应返回 body");
            assertEquals(String.valueOf(fullLength), head.header("Content-Length"),
                    "HEAD 应保留真实 Content-Length（RFC 7231 §4.3.2）");
        } finally {
            head.close();
        }
    }

    // ==================== welcome page ====================

    @Test
    void directoryPath_servesIndexHtml() throws Exception {
        okhttp3.Response resp = get("/e2e-welcome/");
        try {
            assertEquals(200, resp.code(), "目录路径应回退 index.html welcome page");
            assertTrue(resp.body().string().contains("e2e-welcome-index"), "应返回 index.html 内容");
        } finally {
            resp.close();
        }
    }

    // ==================== 路径穿越 ====================

    @Test
    void pathTraversal_rejectedAs404() throws Exception {
        okhttp3.Response resp = get("/e2e-note.txt/../../application.properties");
        try {
            assertTrue(resp.code() == 404 || resp.code() == 400, "路径穿越应被拒绝（404/400），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void missingResource_returns404() throws Exception {
        okhttp3.Response resp = get("/e2e-definitely-missing.txt");
        try {
            assertEquals(404, resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== gzip 变体 ====================

    @Test
    void gzipVariant_servedWhenAccepted() throws Exception {
        okhttp3.Response resp = get("/e2e-note.txt", "Accept-Encoding", "gzip");
        try {
            assertEquals(200, resp.code());
            assertEquals("gzip", resp.header("Content-Encoding"), "存在 .gz 预压缩变体时应返回 Content-Encoding: gzip");
        } finally {
            resp.close();
        }
    }

    @Test
    void gzipVariant_notServedWithoutAcceptEncoding() throws Exception {
        okhttp3.Response resp = get("/e2e-note.txt");
        try {
            assertEquals(200, resp.code());
            assertEquals(null, resp.header("Content-Encoding"), "未声明 Accept-Encoding: gzip 时不应返回压缩变体");
        } finally {
            resp.close();
        }
    }

    private static okhttp3.Response call(OkHttpClient client, okhttp3.Request request) throws Exception {
        return client.newCall(request).execute();
    }
}
