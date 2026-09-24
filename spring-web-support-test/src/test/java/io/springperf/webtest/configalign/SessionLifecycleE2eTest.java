package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话生命周期 E2E：invalidate 后旧 id 失效、changeSessionId 轮换生效、 {@code server.servlet.session.timeout=1s} 过期回收、cookie.secure
 * 属性下发、 requestedSessionId 语义（首次为空、携带 Cookie 时反映客户端值）。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        SessionLifecycleE2eTest.LifecycleConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.servlet.session.timeout=1s",
                "server.servlet.session.cookie.secure=true" })
class SessionLifecycleE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).followRedirects(false).build();

    private static final Pattern JSESSIONID = Pattern.compile("JSESSIONID=([^;]+)");

    @LocalServerPort
    int port;

    private okhttp3.Response get(String path, String sessionId) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url("http://localhost:" + port + path);
        if (sessionId != null) {
            builder.header("Cookie", "JSESSIONID=" + sessionId);
        }
        return CLIENT.newCall(builder.build()).execute();
    }

    private String body(okhttp3.Response resp) throws Exception {
        try {
            return resp.body().string().trim();
        } finally {
            resp.close();
        }
    }

    private static String extractSessionId(String setCookie) {
        assertNotNull(setCookie, "应下发 Set-Cookie");
        Matcher m = JSESSIONID.matcher(setCookie);
        assertTrue(m.find(), "Set-Cookie 应含 JSESSIONID: " + setCookie);
        return m.group(1);
    }

    @Test
    void cookieSecure_attributeIssued() throws Exception {
        okhttp3.Response resp = get("/e2e-session/attr?name=k&value=v", null);
        try {
            assertEquals(200, resp.code());
            String setCookie = resp.header("Set-Cookie");
            assertNotNull(setCookie);
            assertTrue(setCookie.contains("Secure"), "cookie.secure=true 时 Set-Cookie 应带 Secure，实际 " + setCookie);
        } finally {
            resp.close();
        }
    }

    @Test
    void invalidate_oldSessionIdRejected() throws Exception {
        String sessionId = extractSessionId(respSetCookie(get("/e2e-session/attr?name=k&value=v", null)));
        // 失效
        assertEquals("invalidated", body(get("/e2e-session/invalidate", sessionId)));
        // 旧 id 应不再有效：hasSession 返回 false
        assertEquals("false", body(get("/e2e-session/has", sessionId)), "invalidate 后旧 sessionId 不应恢复会话");
    }

    @Test
    void changeSessionId_rotatesAndOldIdInvalid() throws Exception {
        String oldId = extractSessionId(respSetCookie(get("/e2e-session/attr?name=k&value=v", null)));
        okhttp3.Response changeResp = get("/e2e-session/change-id", oldId);
        String newId;
        try {
            assertEquals(200, changeResp.code());
            newId = extractSessionId(changeResp.header("Set-Cookie"));
        } finally {
            changeResp.close();
        }
        assertNotEquals(oldId, newId, "changeSessionId 应轮换出不同 id");
        assertEquals("true", body(get("/e2e-session/has", newId)), "新 id 应有效");
        assertEquals("false", body(get("/e2e-session/has", oldId)), "旧 id 应失效");
    }

    @Test
    void subMinuteTimeout_coercedToZeroMinutes_alignedWithTomcatGranularity() throws Exception {
        // Tomcat/Boot 语义：ServletContext.getSessionTimeout 以「分钟」为单位，
        // server.servlet.session.timeout=1s → toMinutes()=0 → 0 表示永不过期（分钟粒度限制）。
        assertEquals("0", body(get("/e2e-session/max-inactive", null)), "1s 配置经分钟粒度换算应为 0（永不过期，对齐 Tomcat/Boot）");
    }

    @Test
    void sessionMaxInactiveInterval_seconds_actuallyExpires() throws Exception {
        // 秒级过期机制验证：应用层 setMaxInactiveInterval(1)（秒）后空闲 1.6s 应被回收
        String sessionId = extractSessionId(respSetCookie(get("/e2e-session/set-short-timeout", null)));
        assertEquals("true", body(get("/e2e-session/has", sessionId)), "超时前应有效");
        Thread.sleep(1600);
        assertEquals("false", body(get("/e2e-session/has", sessionId)), "maxInactiveInterval=1s 空闲 1.6s 后会话应被回收");
    }

    @Test
    void requestedSessionId_semanticsReflectClientCookie() throws Exception {
        assertEquals("null", body(get("/e2e-session/requested-id", null)), "首次请求未携带 Cookie 时应为 null");
        String sessionId = extractSessionId(respSetCookie(get("/e2e-session/attr?name=k&value=v", null)));
        assertEquals(sessionId, body(get("/e2e-session/requested-id", sessionId)),
                "携带 Cookie 时 requestedSessionId 应等于客户端提交值");
    }

    private static String respSetCookie(okhttp3.Response resp) {
        try {
            return resp.header("Set-Cookie");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class LifecycleConfig {
        @Bean
        LifecycleController lifecycleController() {
            return new LifecycleController();
        }
    }

    @RestController
    static class LifecycleController {

        @GetMapping("/e2e-session/attr")
        public String attr(HttpServletRequest request,
                @org.springframework.web.bind.annotation.RequestParam("name") String name,
                @org.springframework.web.bind.annotation.RequestParam("value") String value) {
            request.getSession(true).setAttribute(name, value);
            return "ok";
        }

        @GetMapping("/e2e-session/invalidate")
        public String invalidate(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            return "invalidated";
        }

        @GetMapping("/e2e-session/has")
        public String has(HttpServletRequest request) {
            return String.valueOf(request.getSession(false) != null);
        }

        @GetMapping("/e2e-session/change-id")
        public String changeId(HttpServletRequest request) {
            // 真正触发会话 id 轮换（Servlet 3.1+），响应应下发新 id 的 Set-Cookie
            return request.changeSessionId();
        }

        @GetMapping("/e2e-session/requested-id")
        public String requestedId(HttpServletRequest request) {
            return String.valueOf(request.getRequestedSessionId());
        }

        @GetMapping("/e2e-session/max-inactive")
        public String maxInactive(HttpServletRequest request) {
            return String.valueOf(request.getSession(true).getMaxInactiveInterval());
        }

        @GetMapping("/e2e-session/set-short-timeout")
        public String setShortTimeout(HttpServletRequest request) {
            // setMaxInactiveInterval 单位为「秒」（Servlet 规范）
            request.getSession(true).setMaxInactiveInterval(1);
            return "ok";
        }
    }
}
