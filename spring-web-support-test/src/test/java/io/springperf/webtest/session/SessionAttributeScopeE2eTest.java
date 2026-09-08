package io.springperf.webtest.session;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E 验证 support 模块新增能力：
 * <ul>
 *   <li>{@code @SessionAttribute}（单数）：从 session 读取单值注入参数</li>
 *   <li>{@code @SessionScope}：session 作用域 bean，跨请求共享、跨 session 隔离</li>
 * </ul>
 * 使用 CookieJar 保持 JSESSIONID 以建立同一 session。
 */
public class SessionAttributeScopeE2eTest extends BaseE2ETest {

    private final OkHttpClient sessionClient;

    public SessionAttributeScopeE2eTest() {
        this.sessionClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .cookieJar(new InMemoryCookieJar())
                .build();
    }

    private String baseUrl() {
        return url("/api/session");
    }

    @Test
    void sessionAttribute_putThenRead_sameSession() throws Exception {
        putGreeting("hello-perf");
        // 同一 session 内可读取 @SessionAttribute 注入值
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/get").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("hello-perf"), "body should contain greeting, got: " + body);
        }
    }

    @Test
    void sessionAttribute_optionalMissing_returnsNull() throws Exception {
        // required=false 且 session 无该属性时返回 null（不报错）
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/get-optional").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"missing\":null"), "expected null missing, got: " + body);
        }
    }

    @Test
    void sessionScope_sharedWithinSession_isolatedAcrossSessions() throws Exception {
        // 同一 session：两次请求共享同一 @SessionScope bean → 计数递增
        assertEquals("1", counterValue());
        assertEquals("2", counterValue());
        assertEquals("3", counterValue());
        // 新 session（无 Cookie）：独立实例 → 从 1 重新开始
        try (Response resp = CLIENT.newCall(new Request.Builder()
                .url(baseUrl() + "/counter").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"count\":1"), "fresh session should start at 1, got: " + body);
        }
    }

    @Test
    void sessionScope_differentSessionId_isolated() throws Exception {
        // 两个独立 CookieJar 会话：各自首次请求都应从 1 开始，且 sessionId 不同
        OkHttpClient clientA = new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar()).build();
        OkHttpClient clientB = new OkHttpClient.Builder()
                .cookieJar(new InMemoryCookieJar()).build();

        Response respA = counterResponse(clientA);
        Response respB = counterResponse(clientB);
        try {
            String bodyA = respA.body().string();
            String bodyB = respB.body().string();
            assertEquals(200, respA.code());
            assertEquals(200, respB.code());
            assertEquals(1, parseCount(bodyA));
            assertEquals(1, parseCount(bodyB));
            assertNotEquals(parseSessionId(bodyA), parseSessionId(bodyB));
        } finally {
            respA.close();
            respB.close();
        }
    }

    // ---- helpers ----

    private void putGreeting(String value) throws Exception {
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/put?name=greeting&value=" + value).get().build()).execute()) {
            assertEquals(200, resp.code());
        }
    }

    private String counterValue() throws Exception {
        return counterValue(sessionClient);
    }

    private String counterValue(OkHttpClient client) throws Exception {
        try (Response resp = counterResponse(client)) {
            assertEquals(200, resp.code());
            return String.valueOf(parseCount(resp.body().string()));
        }
    }

    private Response counterResponse(OkHttpClient client) throws Exception {
        return client.newCall(new Request.Builder()
                .url(baseUrl() + "/counter").get().build()).execute();
    }

    private static int parseCount(String body) {
        int idx = body.indexOf("\"count\":");
        if (idx < 0) {
            throw new IllegalStateException("count not found in body: " + body);
        }
        int start = idx + "\"count\":".length();
        int end = body.indexOf(',', start);
        if (end < 0) {
            end = body.indexOf('}', start);
        }
        return Integer.parseInt(body.substring(start, end).trim());
    }

    private static String parseSessionId(String body) {
        int idx = body.indexOf("\"sessionId\":\"");
        if (idx < 0) {
            throw new IllegalStateException("sessionId not found in body: " + body);
        }
        int start = idx + "\"sessionId\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    /** 简单的内存 CookieJar，跨请求保持 session Cookie。 */
    private static class InMemoryCookieJar implements CookieJar {

        private final Map<String, List<Cookie>> cache = new HashMap<>();

        @Override
        public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            cache.put(url.host(), new ArrayList<>(cookies));
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl url) {
            List<Cookie> cookies = cache.get(url.host());
            return cookies != null ? new ArrayList<>(cookies) : new ArrayList<>();
        }
    }
}
