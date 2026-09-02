package io.springperf.webtest;

import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E 条件请求与 HEAD 语义测试。
 * <p>覆盖静态资源的 ETag / If-None-Match / If-Modified-Since → 304，以及 HEAD 无 body。</p>
 */
public class ConditionalRequestE2ETest extends BaseE2ETest {

    private String staticUrl() {
        return url("/api/static");
    }

    @Test
    void staticResource_returnsEtagAndLastModified() throws Exception {
        Request req = new Request.Builder().url(staticUrl() + "/test.txt").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("Hello, Static Resource!"));
            String etag = resp.header("ETag");
            assertNotNull(etag, "静态资源应返回 ETag");
            assertNotNull(resp.header("Last-Modified"), "静态资源应返回 Last-Modified");
        }
    }

    @Test
    void ifNoneMatch_matchingEtag_returns304() throws Exception {
        // 先 GET 获取 ETag
        Request get = new Request.Builder().url(staticUrl() + "/test.txt").get().build();
        String etag;
        try (Response resp = CLIENT.newCall(get).execute()) {
            etag = resp.header("ETag");
            assertNotNull(etag);
        }
        // 带 If-None-Match 请求
        Request req = new Request.Builder()
                .url(staticUrl() + "/test.txt")
                .header("If-None-Match", etag)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(304, resp.code(), "ETag 匹配应返回 304");
        }
    }

    @Test
    void ifNoneMatch_star_returns304() throws Exception {
        Request req = new Request.Builder()
                .url(staticUrl() + "/test.txt")
                .header("If-None-Match", "*")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(304, resp.code(), "If-None-Match: * 应返回 304");
        }
    }

    @Test
    void ifNoneMatch_mismatchedEtag_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(staticUrl() + "/test.txt")
                .header("If-None-Match", "\"non-existent-etag\"")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "ETag 不匹配应返回 200");
            assertTrue(resp.body().string().contains("Hello"));
        }
    }

    @Test
    void ifModifiedSince_futureDate_returns304() throws Exception {
        // 未来时间戳必定 ≥ Last-Modified → 304
        String future = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
                .plusDays(1)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        Request req = new Request.Builder()
                .url(staticUrl() + "/test.txt")
                .header("If-Modified-Since", future)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(304, resp.code(), "If-Modified-Since 晚于 Last-Modified 应返回 304");
        }
    }

    @Test
    void ifModifiedSince_epoch_returns200() throws Exception {
        // 1970 起点必定早于 Last-Modified → 200 + 完整 body
        Request req = new Request.Builder()
                .url(staticUrl() + "/test.txt")
                .header("If-Modified-Since", "Thu, 01 Jan 1970 00:00:00 GMT")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code(), "If-Modified-Since 早于 Last-Modified 应返回 200");
            assertTrue(resp.body().string().contains("Hello"));
        }
    }

    @Test
    void headRequest_staticResource_noBodyButHeaders() throws Exception {
        Request req = new Request.Builder().url(staticUrl() + "/test.txt").head().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "HEAD 不应有 body，实际: " + body);
            // HEAD 仍应返回真实元数据头
            assertNotNull(resp.header("Content-Length"), "HEAD 应返回 Content-Length");
            assertNotNull(resp.header("ETag"), "HEAD 应返回 ETag");
            assertNotNull(resp.header("Last-Modified"), "HEAD 应返回 Last-Modified");
            assertNotNull(resp.header("Content-Type"), "HEAD 应返回 Content-Type");
        }
    }

    @Test
    void headRequest_subdirectoryResource_noBody() throws Exception {
        Request req = new Request.Builder().url(staticUrl() + "/sub/index.html").head().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "HEAD 不应有 body，实际: " + body);
        }
    }

    @Test
    void headRequest_missingResource_returns404() throws Exception {
        Request req = new Request.Builder().url(staticUrl() + "/nonexistent.txt").head().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code(), "HEAD 不存在的资源应返回 404");
        }
    }

    @Test
    void headRequest_conditionalEtag_returns304() throws Exception {
        // HEAD + If-None-Match：匹配时同样返回 304
        Request get = new Request.Builder().url(staticUrl() + "/test.txt").get().build();
        String etag;
        try (Response resp = CLIENT.newCall(get).execute()) {
            etag = resp.header("ETag");
        }
        Request req = new Request.Builder()
                .url(staticUrl() + "/test.txt")
                .header("If-None-Match", etag)
                .head()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(304, resp.code(), "HEAD + ETag 匹配应返回 304");
        }
    }
}
