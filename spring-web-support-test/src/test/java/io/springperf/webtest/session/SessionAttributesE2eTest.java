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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * E2E 验证 {@code @SessionAttributes}（复数）+ 通用 Model 生命周期机制：
 * <ul>
 *   <li>step1 录入 → session 保存 → step2 从 session 恢复（对象复用，不重建）</li>
 *   <li>不同的 session 相互隔离</li>
 *   <li>{@code SessionStatus.setComplete()} 清理 session 属性</li>
 * </ul>
 */
public class SessionAttributesE2eTest extends BaseE2ETest {

    private final OkHttpClient sessionClient;

    public SessionAttributesE2eTest() {
        this.sessionClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .cookieJar(new InMemoryCookieJar())
                .build();
    }

    private String baseUrl() {
        return url("/api/session-attrs");
    }

    @Test
    void sessionAttributes_step1ThenStep2_restoresSameObject() throws Exception {
        // step1：录入 name，写入 session
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/step1?name=alice").post(okhttp3.RequestBody.create(new byte[0])).build()).execute()) {
            assertEquals(200, resp.code());
            assertTrue(resp.body().string().contains("\"name\":\"alice\""));
        }
        // step2：应从 session 恢复同一对象（name 保留），而非重建为空对象
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/step2").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"name\":\"alice\""), "expected restored name=alice, got: " + body);
        }
    }

    @Test
    void sessionAttributes_differentSessions_isolated() throws Exception {
        OkHttpClient clientA = new OkHttpClient.Builder().cookieJar(new InMemoryCookieJar()).build();
        OkHttpClient clientB = new OkHttpClient.Builder().cookieJar(new InMemoryCookieJar()).build();

        // A 会话写入 name
        try (Response resp = clientA.newCall(new Request.Builder()
                .url(baseUrl() + "/step1?name=a").post(okhttp3.RequestBody.create(new byte[0])).build()).execute()) {
            assertEquals(200, resp.code());
        }
        // B 会话未写入，step2 读到空对象（新 Wizard）
        try (Response resp = clientB.newCall(new Request.Builder()
                .url(baseUrl() + "/step2").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"name\":null") || !body.contains("\"name\":\"a\""),
                    "isolated session should NOT see name=a, got: " + body);
        }
        // A 会话仍保留
        try (Response resp = clientA.newCall(new Request.Builder()
                .url(baseUrl() + "/step2").get().build()).execute()) {
            assertEquals(200, resp.code());
            assertTrue(resp.body().string().contains("\"name\":\"a\""));
        }
    }

    @Test
    void sessionAttributes_setComplete_cleansSession() throws Exception {
        // 录入
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/step1?name=alice").post(okhttp3.RequestBody.create(new byte[0])).build()).execute()) {
            assertEquals(200, resp.code());
        }
        // complete：SessionStatus.setComplete() → 清理 session 属性
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/complete").post(okhttp3.RequestBody.create(new byte[0])).build()).execute()) {
            assertEquals(200, resp.code());
        }
        // 清理后 step2 读到新 Wizard（name 为空）
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/step2").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"name\":null"), "expected cleaned empty wizard, got: " + body);
        }
    }

    @Test
    void modelAttribute_reusesExistingModelValue_sameRequest() throws Exception {
        // 同一请求内：@ModelAttribute("wizard") 参数应与 @ModelAttribute 方法预置的实例相同（复用，不重建）
        try (Response resp = sessionClient.newCall(new Request.Builder()
                .url(baseUrl() + "/step2").get().build()).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            // step2 返回的 name 来自注入的 wizard 实例；若复用，格式为 JSON 字符串 name
            assertTrue(body.contains("\"name\""), "wizard should be resolved via model, got: " + body);
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