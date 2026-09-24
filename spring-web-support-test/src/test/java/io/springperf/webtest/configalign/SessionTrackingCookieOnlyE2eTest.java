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
 * {@code server.servlet.session.tracking-modes=cookie}（显式声明）E2E： 只走 Cookie，{@code encodeURL} 不得重写——这正是 Servlet 规范的默认语义。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        SessionTrackingCookieOnlyE2eTest.CookieOnlyConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.servlet.session.tracking-modes=cookie" })
class SessionTrackingCookieOnlyE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    @Test
    void cookieOnly_noUrlRewrite() throws Exception {
        Response resp = CLIENT
                .newCall(new Request.Builder().url("http://localhost:" + port + "/e2e-tkc/session").get().build())
                .execute();
        try {
            assertEquals(200, resp.code());
            assertNotNull(resp.header("Set-Cookie"), "cookie 模式应下发 Set-Cookie");
            assertTrue(resp.body().string().contains("\"encodedUrl\":\"/e2e-tkc/next\""),
                    "cookie 模式下 encodeURL 不得追加 ;jsessionid=");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class CookieOnlyConfig {
        @Bean
        CookieOnlyController cookieOnlyController() {
            return new CookieOnlyController();
        }
    }

    @RestController
    static class CookieOnlyController {
        @GetMapping("/e2e-tkc/session")
        public String session(HttpServletRequest request, HttpServletResponse response) {
            request.getSession(true);
            return "{\"encodedUrl\":\"" + response.encodeURL("/e2e-tkc/next") + "\"}";
        }
    }
}
