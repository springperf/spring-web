package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 静态资源边界语义 E2E：
 *
 * <ul>
 *   <li>条件请求与 Range 并存时，304 优先（未修改就无需传分片）；</li>
 *   <li>206 分片响应同样携带 Cache-Control / Accept-Ranges（缓存指令不得因分片丢失）；</li>
 *   <li>预压缩 {@code *.gz} 变体：按 Accept-Encoding 协商选取，必须声明
 *       {@code Vary: Accept-Encoding}（否则共享缓存会把 gzip 表示回给不支持的客户端），
 *       且不做 Range 切片（切片后的 gzip 流不可解码）；</li>
 *   <li>目录无 index.html、编码后的路径穿越均 404。</li>
 * </ul>
 */
@SpringBootTest(classes = ConfigAlignTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true",
                "spring.web.resources.cache.period=3600"
        })
class StaticResourceEdgeE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private Response get(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    // ==================== 条件请求 × Range ====================

    @Test
    void ifNoneMatchMatch_withRange_returns304Not206() throws Exception {
        Response base = get("/e2e-range.txt");
        String etag;
        try {
            etag = base.header("ETag");
            base.body().string();
        } finally {
            base.close();
        }
        assertNotNull(etag);

        Response resp = get("/e2e-range.txt", "If-None-Match", etag, "Range", "bytes=0-9");
        try {
            assertEquals(304, resp.code(),
                    "资源未修改时无需返回分片，应为 304（不得为 206），实际 " + resp.code());
            assertNull(resp.header("Content-Range"), "304 不应带 Content-Range");
        } finally {
            resp.close();
        }
    }

    @Test
    void rangeAndIfRangeStale_fallsBackToFullEntityWithCacheControl() throws Exception {
        Response resp = get("/e2e-range.txt", "Range", "bytes=0-9", "If-Range", "\"stale\"");
        try {
            assertEquals(200, resp.code(), "If-Range 未命中应回整实体，实际 " + resp.code());
            assertEquals("max-age=3600, must-revalidate", resp.header("Cache-Control"),
                    "整实体响应应带 Cache-Control");
        } finally {
            resp.close();
        }
    }

    // ==================== 206 的缓存契约 ====================

    @Test
    void partialResponse_keepsCacheControlAndAcceptRanges() throws Exception {
        Response resp = get("/e2e-range.txt", "Range", "bytes=0-9");
        try {
            assertEquals(206, resp.code());
            assertEquals("max-age=3600, must-revalidate", resp.header("Cache-Control"),
                    "206 分片响应必须携带缓存指令（否则分片请求永远不缓存），实际 "
                            + resp.header("Cache-Control"));
            assertEquals("bytes", resp.header("Accept-Ranges"),
                    "206 应保留 Accept-Ranges，实际 " + resp.header("Accept-Ranges"));
        } finally {
            resp.close();
        }
    }

    // ==================== gzip 预压缩变体 ====================

    @Test
    void gzipVariant_declaresVaryAcceptEncoding() throws Exception {
        Response resp = get("/e2e-note.txt", "Accept-Encoding", "gzip");
        try {
            assertEquals(200, resp.code(), "存在 .gz 变体时应命中，实际 " + resp.code());
            assertEquals("gzip", resp.header("Content-Encoding"));
            String vary = String.join(",", resp.headers("Vary"));
            assertTrue(vary.contains("Accept-Encoding"),
                    "按 Accept-Encoding 协商选出的表示必须声明 Vary，实际 Vary=" + vary);
        } finally {
            resp.close();
        }
    }

    @Test
    void withoutGzipAcceptEncoding_servesPlainEntity() throws Exception {
        Response resp = get("/e2e-note.txt", "Accept-Encoding", "identity");
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "未声明 gzip 时不得下发压缩表示");
            assertTrue(resp.body().string().length() > 0);
        } finally {
            resp.close();
        }
    }

    @Test
    void gzipVariant_withRange_servesWholeEncodedEntity() throws Exception {
        Response resp = get("/e2e-note.txt", "Accept-Encoding", "gzip", "Range", "bytes=0-9");
        try {
            assertEquals(200, resp.code(),
                    "对压缩表示不支持切片（切片后的 gzip 流不可解码），应回整实体，实际 " + resp.code());
            assertNull(resp.header("Content-Range"), "不得声称返回了分片");
            assertEquals("gzip", resp.header("Content-Encoding"));
        } finally {
            resp.close();
        }
    }

    // ==================== 目录与穿越 ====================

    @Test
    void rootWithoutIndexHtml_returns404() throws Exception {
        Response resp = get("/");
        try {
            assertEquals(404, resp.code(),
                    "静态根目录无 index.html 时应 404（不得因 add-mappings 命中目录本身），实际 "
                            + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void encodedPathTraversal_rejected() throws Exception {
        Response resp = get("/%2e%2e/%2e%2e/etc/passwd");
        try {
            assertTrue(resp.code() == 404 || resp.code() == 400,
                    "编码后的路径穿越应被拒绝，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }
}
