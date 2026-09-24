package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.session.tracking-modes=cookie,url} E2E：同时启用两种跟踪方式时， Set-Cookie 与
 * {@code encodeURL/encodeRedirectURL} 重写都必须生效，且**请求 URI 中的 {@code ;jsessionid=} 必须被识别为会话来源**（Servlet 规范 §7.1 URL 重写）。
 * <p>
 * 这是 URL 跟踪模式的闭环条件：服务端把 id 写进 URL（写），客户端下一跳带着 URL 回来（读）。 只写不读会让 URL 跟踪模式在跨请求时静默丢会话。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        SessionTrackingModesE2eTest.TrackingConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.servlet.session.tracking-modes=cookie,url" })
class SessionTrackingModesE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    private static final Pattern JSESSIONID_IN_URL = Pattern.compile(";jsessionid=([^/?]+)");

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void bothModes_enabled_cookieAndUrlRewriteCoexist() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder().url(url("/e2e-tk/put?value=v1")).get().build()).execute();
        try {
            assertEquals(200, resp.code());
            String setCookie = resp.header("Set-Cookie");
            assertNotNull(setCookie, "cookie 跟踪模式应下发 Set-Cookie");
            assertTrue(setCookie.contains("JSESSIONID="), "实际 Set-Cookie=" + setCookie);

            String body = resp.body().string();
            String encoded = field(body, "encodedUrl");
            assertTrue(encoded != null && encoded.contains(";jsessionid="), "url 跟踪模式应重写 encodeURL，实际 body=" + body);
            String encodedRedirect = field(body, "encodedRedirect");
            assertTrue(encodedRedirect != null && encodedRedirect.contains(";jsessionid="),
                    "encodeRedirectURL 同样应被重写，实际 body=" + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void urlMode_sessionIdInRequestUri_isRecognized() throws Exception {
        // 写入会话并拿到被重写的 URL
        String encodedUrl;
        String sessionId;
        Response put = CLIENT.newCall(new Request.Builder().url(url("/e2e-tk/put?value=url-roundtrip")).get().build())
                .execute();
        try {
            assertEquals(200, put.code());
            encodedUrl = field(put.body().string(), "encodedUrl");
            assertNotNull(encodedUrl, "应返回重写后的 URL");
            Matcher m = JSESSIONID_IN_URL.matcher(encodedUrl);
            assertTrue(m.find(), "重写后的 URL 应含 ;jsessionid=，实际 " + encodedUrl);
            sessionId = m.group(1);
        } finally {
            put.close();
        }

        // 关键：不带 Cookie，仅凭 URI 中的 ;jsessionid= 回访，会话必须被识别
        String path = encodedUrl.replace(";jsessionid=" + sessionId, ";jsessionid=" + sessionId);
        Response get = CLIENT.newCall(new Request.Builder().url(url(path)).build()).execute();
        try {
            assertEquals(200, get.code(), "URI 携带 ;jsessionid= 的请求应正常路由（矩阵参数不得影响路径匹配），实际 " + get.code());
            String body = get.body().string();
            assertEquals("url-roundtrip", field(body, "value"), "URI 中的 ;jsessionid= 应作为会话来源被识别，实际 body=" + body);
            assertEquals("true", field(body, "fromUrl"), "isRequestedSessionIdFromURL() 应返回 true，实际 body=" + body);
            assertEquals(sessionId, field(body, "requested"),
                    "getRequestedSessionId() 应返回 URI 中的会话 id，实际 body=" + body);
            assertTrue(field(body, "requestUri").contains(";jsessionid="),
                    "getRequestURI() 应保留原始 URI（含路径参数），实际 body=" + body);
            assertTrue(!field(body, "servletPath").contains(";jsessionid="),
                    "getServletPath() 应已剥离路径参数（Tomcat 同语义），实际 body=" + body);
        } finally {
            get.close();
        }
    }

    @Test
    void urlMode_withCookiePresent_doesNotRewrite() throws Exception {
        Response first = CLIENT.newCall(new Request.Builder().url(url("/e2e-tk/put?value=v2")).get().build()).execute();
        String cookie;
        try {
            assertEquals(200, first.code());
            cookie = first.header("Set-Cookie").split(";")[0];
        } finally {
            first.close();
        }

        Response second = CLIENT
                .newCall(new Request.Builder().url(url("/e2e-tk/init")).header("Cookie", cookie).get().build())
                .execute();
        try {
            assertEquals(200, second.code());
            String encoded = field(second.body().string(), "encodedUrl");
            assertTrue(encoded != null && !encoded.contains(";jsessionid="),
                    "客户端已携带 Cookie 时 encodeURL 不应再重写（规范要求避免重复暴露 id），实际 " + encoded);
        } finally {
            second.close();
        }
    }

    @TestConfiguration
    static class TrackingConfig {
        @Bean
        TrackingController trackingController() {
            return new TrackingController();
        }
    }

    @RestController
    static class TrackingController {

        @GetMapping("/e2e-tk/put")
        public Map<String, String> put(HttpServletRequest request, HttpServletResponse response,
                @RequestParam String value) {
            request.getSession(true).setAttribute("v", value);
            Map<String, String> out = new LinkedHashMap<>();
            out.put("encodedUrl", response.encodeURL("/e2e-tk/get"));
            out.put("encodedRedirect", response.encodeRedirectURL("/e2e-tk/get"));
            return out;
        }

        @GetMapping("/e2e-tk/init")
        public Map<String, String> init(HttpServletRequest request, HttpServletResponse response) {
            request.getSession(true);
            Map<String, String> out = new LinkedHashMap<>();
            out.put("encodedUrl", response.encodeURL("/e2e-tk/get"));
            return out;
        }

        @GetMapping("/e2e-tk/get")
        public Map<String, String> get(HttpServletRequest request) {
            HttpSession session = request.getSession(false);
            Object v = session == null ? null : session.getAttribute("v");
            Map<String, String> out = new LinkedHashMap<>();
            out.put("value", v == null ? "no-session" : String.valueOf(v));
            out.put("fromUrl", String.valueOf(request.isRequestedSessionIdFromURL()));
            out.put("requested", String.valueOf(request.getRequestedSessionId()));
            out.put("requestUri", request.getRequestURI());
            out.put("servletPath", request.getServletPath());
            return out;
        }
    }
}
