package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 条件请求（RFC 9110 §13）E2E：静态资源的 ETag / Last-Modified 校验语义。
 *
 * <ul>
 *   <li>If-None-Match：精确命中 → 304；`*` → 304；**弱比较**（{@code W/"..."}）→ 304；
 *       多值列表中任一命中即 304；无命中 → 200 + 实体；</li>
 *   <li>If-Modified-Since：等于/晚于最后修改 → 304；早于最后修改 → 200；非法日期忽略；</li>
 *   <li>优先级：两者并存时 If-None-Match 优先（RFC 9110 §13.1.3）——即使日期更晚也不得 304；</li>
 *   <li>304 必须无 body，且携带 ETag/Last-Modified/Cache-Control 等校验与缓存指令。</li>
 * </ul>
 */
@SpringBootTest(classes = ConfigAlignTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true",
                "spring.web.resources.cache.period=3600"
        })
class ConditionalRequestE2eTest {

    private static final String PATH = "/e2e-range.txt";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private Response get(String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + PATH).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    /** 基线校验器（ETag / Last-Modified）。 */
    private String[] validators() throws Exception {
        Response resp = get();
        try {
            assertEquals(200, resp.code());
            String etag = resp.header("ETag");
            String lastModified = resp.header("Last-Modified");
            assertNotNull(etag, "静态资源应携带 ETag");
            assertNotNull(lastModified, "静态资源应携带 Last-Modified");
            return new String[]{etag, lastModified};
        } finally {
            resp.close();
        }
    }

    private static String shiftDate(String httpDate, long seconds) {
        ZonedDateTime t = ZonedDateTime.parse(httpDate, DateTimeFormatter.RFC_1123_DATE_TIME);
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(t.plusSeconds(seconds));
    }

    // ==================== If-None-Match ====================

    @Test
    void ifNoneMatch_exactMatch_returns304WithoutBody() throws Exception {
        String etag = validators()[0];
        Response resp = get("If-None-Match", etag);
        try {
            assertEquals(304, resp.code(), "ETag 命中应 304");
            assertEquals("", resp.body().string(), "304 不得携带 body");
            assertEquals(etag, resp.header("ETag"), "304 应回带 ETag");
            assertNotNull(resp.header("Last-Modified"), "304 应回带 Last-Modified");
        } finally {
            resp.close();
        }
    }

    @Test
    void ifNoneMatch_star_returns304() throws Exception {
        validators();
        Response resp = get("If-None-Match", "*");
        try {
            assertEquals(304, resp.code(), "If-None-Match: * 表示任意版本都在缓存中 → 304");
        } finally {
            resp.close();
        }
    }

    @Test
    void ifNoneMatch_weakEtag_returns304() throws Exception {
        // RFC 9110 §13.1.2：If-None-Match 使用**弱比较**，W/"x" 与 "x" 视为匹配
        // （部分 CDN/代理会把强 ETag 弱化后回传，若按强比较会持续 200，缓存命中率归零）
        String etag = validators()[0];
        Response resp = get("If-None-Match", "W/" + etag);
        try {
            assertEquals(304, resp.code(),
                    "弱 ETag 应参与弱比较命中 304，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifNoneMatch_multipleValues_oneMatches_returns304() throws Exception {
        String etag = validators()[0];
        Response resp = get("If-None-Match", "\"other-1\", " + etag + ", \"other-2\"");
        try {
            assertEquals(304, resp.code(), "多值列表中任一命中即 304，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifNoneMatch_multipleValues_noneMatches_returns200() throws Exception {
        validators();
        Response resp = get("If-None-Match", "\"other-1\", \"other-2\"");
        try {
            assertEquals(200, resp.code(), "多值列表均不命中应返回实体");
            assertTrue(resp.body().string().length() > 0);
        } finally {
            resp.close();
        }
    }

    @Test
    void ifNoneMatch_stale_takesPrecedenceOverFreshIfModifiedSince() throws Exception {
        // RFC 9110 §13.1.3：If-None-Match 存在时**不得**再评估 If-Modified-Since
        String[] v = validators();
        Response resp = get("If-None-Match", "\"stale\"", "If-Modified-Since", v[1]);
        try {
            assertEquals(200, resp.code(),
                    "If-None-Match 未命中时若按 If-Modified-Since 返回 304 属错误优先级，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== If-Modified-Since ====================

    @Test
    void ifModifiedSince_exactDate_returns304() throws Exception {
        String lastModified = validators()[1];
        Response resp = get("If-Modified-Since", lastModified);
        try {
            assertEquals(304, resp.code(), "未修改应 304，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifModifiedSince_laterDate_returns304() throws Exception {
        String lastModified = validators()[1];
        Response resp = get("If-Modified-Since", shiftDate(lastModified, 3600));
        try {
            assertEquals(304, resp.code(), "客户端日期晚于最后修改 → 304，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifModifiedSince_olderDate_returns200() throws Exception {
        String lastModified = validators()[1];
        Response resp = get("If-Modified-Since", shiftDate(lastModified, -86400));
        try {
            assertEquals(200, resp.code(), "客户端日期早于最后修改 → 应返回实体，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifModifiedSince_invalidDate_ignored() throws Exception {
        validators();
        Response resp = get("If-Modified-Since", "not-a-date");
        try {
            assertEquals(200, resp.code(), "非法日期应被忽略（按无条件下请求处理），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== 304 的响应契约 ====================

    @Test
    void notModified_keepsCacheControlAndValidators() throws Exception {
        String etag = validators()[0];
        Response resp = get("If-None-Match", etag);
        try {
            assertEquals(304, resp.code());
            assertEquals("max-age=3600, must-revalidate", resp.header("Cache-Control"),
                    "304 应回带 Cache-Control，便于客户端刷新新鲜度，实际 " + resp.header("Cache-Control"));
            assertEquals(etag, resp.header("ETag"));
        } finally {
            resp.close();
        }
    }

    @Test
    void head_withIfNoneMatchMatch_returns304() throws Exception {
        String etag = validators()[0];
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + PATH)
                .header("If-None-Match", etag)
                .head().build()).execute();
        try {
            assertEquals(304, resp.code(), "HEAD 条件请求同样应 304，实际 " + resp.code());
            assertEquals("", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void ifNoneMatch_lowercaseWeakPrefix_returns304() throws Exception {
        // 弱前缀大小写不应影响匹配（RFC 7232 起 token 不区分大小写）
        String etag = validators()[0];
        Response resp = get("If-None-Match", "w/" + etag);
        try {
            assertEquals(304, resp.code(), "小写 w/ 前缀亦应弱比较命中，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }
}
