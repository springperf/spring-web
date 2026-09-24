package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.session.cookie.*} E2E：自定义 Cookie 名、 http-only 开关、SameSite 属性真实作用于下发的 Set-Cookie 头。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        SessionCookieE2eTest.SessionInitConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.servlet.session.cookie.name=XID",
                "server.servlet.session.cookie.http-only=false", "server.servlet.session.cookie.same-site=Strict" })
class SessionCookieE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String setCookie() throws Exception {
        okhttp3.Response resp = CLIENT
                .newCall(new okhttp3.Request.Builder().url("http://localhost:" + port + "/e2e-cookie/session").build())
                .execute();
        try {
            assertEquals(200, resp.code());
            return resp.header("Set-Cookie");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class SessionInitConfig {
        @Bean
        SessionInitController sessionInitController() {
            return new SessionInitController();
        }
    }

    @RestController
    static class SessionInitController {
        @org.springframework.web.bind.annotation.GetMapping("/e2e-cookie/session")
        public String init(jakarta.servlet.http.HttpServletRequest request) {
            request.getSession(true);
            return "ok";
        }
    }

    @Test
    void customCookieName_applied() throws Exception {
        String cookie = setCookie();
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("XID="), "应使用自定义 Cookie 名 XID，实际 " + cookie);
    }

    @Test
    void httpOnlyFalse_omitsFlag() throws Exception {
        String cookie = setCookie();
        assertNotNull(cookie);
        assertFalse(cookie.toLowerCase().contains("httponly"),
                "http-only=false 时 Set-Cookie 不应带 HttpOnly，实际 " + cookie);
    }

    @Test
    void sameSiteStrict_applied() throws Exception {
        String cookie = setCookie();
        assertNotNull(cookie);
        assertTrue(cookie.contains("SameSite=Strict"), "Set-Cookie 应带 SameSite=Strict，实际 " + cookie);
    }
}
