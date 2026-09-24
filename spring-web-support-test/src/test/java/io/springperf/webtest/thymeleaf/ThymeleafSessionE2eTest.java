package io.springperf.webtest.thymeleaf;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E：Servlet 场景下 Thymeleaf 模板读取真实 session。
 * <p>
 * 验证链路：{@code ServletWebExchangeProvider} → {@code ServletWebSession}（桥接 HttpSession） → {@code ThymeleafWebContext} 把
 * session 属性注入模板上下文变量。
 * </p>
 * <p>
 * 回归保护：若 provider 未生效（回退默认实现），session 为 null， 模板将渲染 {@code no-session}，测试失败。
 * </p>
 * <p>
 * 注：Thymeleaf 3.1 起 {@code #session} 表达式对象已被官方移除，故通过上下文变量读取。
 * </p>
 */
class ThymeleafSessionE2eTest extends BaseE2ETest {

    private final OkHttpClient sessionClient = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).cookieJar(new InMemoryCookieJar())
            .build();

    private String base() {
        return url("/api/thymeleaf-session");
    }

    @Test
    void thymeleafSession_readsSessionAttribute() throws Exception {
        try (Response resp = sessionClient.newCall(new Request.Builder().url(base()).get().build()).execute()) {
            String body = resp.body().string();
            String flat = body.replace("\r", "").replace("\n", "~");
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + flat);
            assertTrue(body.contains("hello-thymeleaf"), "模型变量应渲染, body=" + flat);
            assertTrue(body.contains("alice"), "session user 应可从模板变量读取, body=" + flat);
            assertTrue(body.contains("session-count= <span>"), "session count 应被注入, body=" + flat);
            assertTrue(!body.contains("no-session"), "session 不应为 null（ServletWebExchangeProvider 应已生效）, body=" + flat);
        }
    }

    @Test
    void thymeleafSession_consecutiveRequests_shareAndIncrementSession() throws Exception {
        // 不依赖固定计数值（其它用例可能已访问过同一会话），改为断言「连续请求间 count 递增」
        int first = fetchSessionCount(sessionClient);
        int second = fetchSessionCount(sessionClient);
        assertEquals(first + 1, second, "同一会话连续请求应共享 session 并使 count 递增: first=" + first + " second=" + second);
    }

    private int fetchSessionCount(OkHttpClient client) throws Exception {
        try (Response resp = client.newCall(new Request.Builder().url(base()).get().build()).execute()) {
            String body = resp.body().string();
            String flat = body.replace("\r", "").replace("\n", "~");
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + flat);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("session-count= <span>(\\d+)</span>")
                    .matcher(body);
            assertTrue(m.find(), "未找到 session-count, body=" + flat);
            return Integer.parseInt(m.group(1));
        }
    }

    @Test
    void thymeleafSession_differentSessions_isolated() throws Exception {
        OkHttpClient other = new OkHttpClient.Builder().cookieJar(new InMemoryCookieJar()).build();
        // 独立会话首次访问 count 应为 1（不应看到 sessionClient 的计数）
        try (Response resp = other.newCall(new Request.Builder().url(base()).get().build()).execute()) {
            String body = resp.body().string();
            String flat = body.replace("\r", "").replace("\n", "~");
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + flat);
            assertTrue(body.contains("session-count= <span>1</span>"), "独立会话应为全新计数 1, body=" + flat);
        }
    }

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
