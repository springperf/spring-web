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
            // 不做 trim：test.txt 无行尾换行，trim 会掩盖多/少字节的问题
            assertEquals("Hello, Static Resource!", resp.body().string(), "静态资源应返回完整内容");
            String etag = resp.header("ETag");
            assertNotNull(etag, "静态资源应返回 ETag");
            assertNotNull(resp.header("Last-Modified"), "静态资源应返回 Last-Modified");
            // WebTestConfig 注册时 setCachePeriod(3600)，此处验证确实落到了响应头
            assertEquals("max-age=3600, must-revalidate", resp.header("Cache-Control"),
                    "静态资源应按 cachePeriod 写出 Cache-Control，实际: " + resp.headers());
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
            // RFC 9110 §15.4.5：304 必须带上与 200 一致的校验器与缓存指令，否则客户端无从续期
            assertEquals(etag, resp.header("ETag"), "304 应携带与 200 相同的 ETag");
            assertNotNull(resp.header("Last-Modified"), "304 应携带 Last-Modified");
            assertNotNull(resp.header("Cache-Control"), "304 应携带 Cache-Control");
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "304 不应有 body，实际: " + body);
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
            assertEquals("Hello, Static Resource!", resp.body().string(), "应返回完整静态资源内容");
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
            assertEquals("Hello, Static Resource!", resp.body().string(), "应返回完整静态资源内容");
        }
    }

    @Test
    void headRequest_staticResource_noBodyButHeaders() throws Exception {
        // 先用 GET 取得真实元数据，HEAD 只断言"存在"无法发现长度/ETag 错配
        Request getReq = new Request.Builder().url(staticUrl() + "/test.txt").get().build();
        int expectedLength;
        String expectedEtag;
        try (Response getResp = CLIENT.newCall(getReq).execute()) {
            expectedLength = getResp.body().bytes().length;
            expectedEtag = getResp.header("ETag");
        }

        Request req = new Request.Builder().url(staticUrl() + "/test.txt").head().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body() != null ? resp.body().string() : null;
            assertTrue(body == null || body.isEmpty(), "HEAD 不应有 body，实际: " + body);
            assertEquals(expectedLength, Integer.parseInt(resp.header("Content-Length")),
                    "HEAD 的 Content-Length 应等于 GET body 字节数");
            assertEquals(expectedEtag, resp.header("ETag"), "HEAD 的 ETag 应与 GET 一致");
            assertNotNull(resp.header("Last-Modified"), "HEAD 应返回 Last-Modified");
            assertEquals("text/plain", resp.header("Content-Type", "").split(";")[0].trim(),
                    "HEAD 的 Content-Type 应为 text/plain，实际: " + resp.header("Content-Type"));
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
