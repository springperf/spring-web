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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.*} E2E：session.timeout（Duration 秒口径）、
 * tracking-modes=url（URL 重写生效且不发 Set-Cookie）、virtual-server-name、
 * application-display-name、context-parameters 显式块。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                ServletConfigE2eTest.ServletKeysConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.session.timeout=1m",
                "server.servlet.session.tracking-modes=url",
                "server.servlet.virtual-server-name=e2e-host",
                "server.servlet.application-display-name=E2eApp",
                "server.servlet.context-parameters.e2e-param=hello"
        })
class ServletConfigE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    private Map<String, String> get(String path) throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url(path)).build()).execute();
        try {
            String raw = resp.body().string();
            assertEquals(200, resp.code(), raw);
            // 简易 JSON 扁平解析：形如 {"k":"v",...}
            java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
            for (String pair : raw.replaceAll("[{}\"]", "").split(",")) {
                int idx = pair.indexOf(':');
                if (idx > 0) {
                    out.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
                }
            }
            return out;
        } finally {
            resp.close();
        }
    }

    @Test
    void sessionTimeout_oneMinute_reflectedAs60s() throws Exception {
        Map<String, String> out = get("/e2e-scfg/session");
        assertEquals("60", out.get("maxInactive"),
                "session.timeout=1m 应解析为 60 秒，实际 " + out);
    }

    @Test
    void trackingModesUrl_rewriteAppendsJsessionid() throws Exception {
        Map<String, String> out = get("/e2e-scfg/rewrite");
        assertTrue(out.get("encodedUrl").contains("jsessionid="),
                "tracking-modes=url 时 encodeURL 应追加 ;jsessionid=...，实际 " + out);
    }

    @Test
    void trackingModesUrl_noSetCookie() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-scfg/session")).build()).execute();
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Set-Cookie"),
                    "纯 URL 跟踪模式不应下发 Cookie，实际 " + resp.header("Set-Cookie"));
        } finally {
            resp.close();
        }
    }

    @Test
    void virtualServerName_configured() throws Exception {
        Map<String, String> out = get("/e2e-scfg/vhost");
        assertEquals("e2e-host", out.get("vhost"), "实际 " + out);
    }

    @Test
    void displayName_configured() throws Exception {
        Map<String, String> out = get("/e2e-scfg/display");
        assertEquals("E2eApp", out.get("displayName"), "实际 " + out);
    }

    @Test
    void contextParameter_exposed() throws Exception {
        Map<String, String> out = get("/e2e-scfg/initparam");
        assertEquals("hello", out.get("e2eParam"), "实际 " + out);
    }

    @TestConfiguration
    static class ServletKeysConfig {
        @Bean
        ServletKeysController servletKeysController() {
            return new ServletKeysController();
        }
    }

    @RestController
    static class ServletKeysController {

        @GetMapping("/e2e-scfg/session")
        public Map<String, String> session(HttpServletRequest request) {
            HttpSession session = request.getSession(true);
            return Map.of("maxInactive", String.valueOf(session.getMaxInactiveInterval()));
        }

        @GetMapping("/e2e-scfg/rewrite")
        public Map<String, String> rewrite(HttpServletRequest request, HttpServletResponse response) {
            request.getSession(true);
            return Map.of("encodedUrl", response.encodeURL("/next-page"));
        }

        @GetMapping("/e2e-scfg/vhost")
        public Map<String, String> vhost(HttpServletRequest request) {
            return Map.of("vhost", request.getServletContext().getVirtualServerName());
        }

        @GetMapping("/e2e-scfg/display")
        public Map<String, String> display(HttpServletRequest request) {
            return Map.of("displayName", String.valueOf(request.getServletContext().getServletContextName()));
        }

        @GetMapping("/e2e-scfg/initparam")
        public Map<String, String> initparam(HttpServletRequest request) {
            return Map.of("e2eParam", String.valueOf(request.getServletContext().getInitParameter("e2e-param")));
        }
    }
}
