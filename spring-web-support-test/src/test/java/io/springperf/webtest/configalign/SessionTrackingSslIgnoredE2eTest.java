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
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.session.tracking-modes=SSL} E2E：SSL 跟踪在 Netty 下无容器 SSL session
 * 概念，实现选择「告警并忽略」——因此不会退化成「无跟踪模式」，会话仍按默认 Cookie 正常工作， 且 {@code encodeURL} 不重写。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        SessionTrackingSslIgnoredE2eTest.SslConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.servlet.session.tracking-modes=SSL" })
class SessionTrackingSslIgnoredE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private Response call(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).get().build()).execute();
    }

    @Test
    void sslModeIgnored_sessionStillWorksViaCookie() throws Exception {
        Response resp = call("/e2e-tks/session");
        try {
            assertEquals(200, resp.code());
            String setCookie = resp.header("Set-Cookie");
            assertNotNull(setCookie, "SSL 被忽略后应回退默认 Cookie 跟踪，仍须下发 Set-Cookie");
            assertTrue(setCookie.contains("JSESSIONID="), "实际 Set-Cookie=" + setCookie);
        } finally {
            resp.close();
        }
    }

    @Test
    void sslModeIgnored_effectiveModesDoNotContainUrl() throws Exception {
        Response resp = call("/e2e-tks/modes");
        try {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("\"hasUrl\":false"), "SSL 被忽略且未配置 URL 时，有效跟踪模式不应含 URL，实际 " + body);
            assertTrue(body.contains("\"hasCookie\":true"), "应保留 Cookie 这一有效跟踪模式，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class SslConfig {
        @Bean
        SslController sslController() {
            return new SslController();
        }
    }

    @RestController
    static class SslController {

        @GetMapping("/e2e-tks/session")
        public String session(HttpServletRequest request, HttpServletResponse response) {
            request.getSession(true);
            return "{\"encodedUrl\":\"" + response.encodeURL("/e2e-tks/next") + "\"}";
        }

        @GetMapping("/e2e-tks/modes")
        public String modes(HttpServletRequest request) {
            java.util.Set<jakarta.servlet.SessionTrackingMode> modes = request.getServletContext()
                    .getEffectiveSessionTrackingModes();
            return "{\"hasUrl\":" + modes.contains(jakarta.servlet.SessionTrackingMode.URL) + ",\"hasCookie\":"
                    + modes.contains(jakarta.servlet.SessionTrackingMode.COOKIE) + "}";
        }
    }
}
