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
 * {@code server.servlet.session.tracking-modes=}（空值）E2E：空值等价于「未配置」，
 * 不得把有效跟踪模式集合置空，会话仍按默认 Cookie 工作且不重写 URL。
 *
 * <p>非法 token（如 {@code nope}）无法用 E2E 验证「容错回退」：该键同时被 Spring Boot 的
 * {@code ServerProperties} 绑定（{@code ignoreInvalidFields=false}），非法枚举值会在应用启动
 * 阶段直接失败。框架侧的逐 token 容错解析仅对非 Boot 的裸用法有效（单测覆盖）。</p>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, SessionTrackingEmptyE2eTest.EmptyConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.servlet.session.tracking-modes="
        })
class SessionTrackingEmptyE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Test
    void emptyValue_fallsBackToDefaultCookieTracking() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/e2e-tke/session").get().build()).execute();
        try {
            assertEquals(200, resp.code());
            String setCookie = resp.header("Set-Cookie");
            assertNotNull(setCookie, "空值不应导致无跟踪模式，会话仍应可用");
            String body = resp.body().string();
            assertTrue(body.contains("\"encodedUrl\":\"/e2e-tke/next\""),
                    "未启用 URL 模式时不得重写，实际 " + body);
            assertTrue(body.contains("\"hasCookie\":true"),
                    "应回退到 Cookie 跟踪模式，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class EmptyConfig {
        @Bean
        EmptyController emptyController() {
            return new EmptyController();
        }
    }

    @RestController
    static class EmptyController {
        @GetMapping("/e2e-tke/session")
        public String session(HttpServletRequest request, HttpServletResponse response) {
            request.getSession(true);
            java.util.Set<jakarta.servlet.SessionTrackingMode> modes =
                    request.getServletContext().getEffectiveSessionTrackingModes();
            return "{\"encodedUrl\":\"" + response.encodeURL("/e2e-tke/next")
                    + "\",\"hasCookie\":" + modes.contains(jakarta.servlet.SessionTrackingMode.COOKIE)
                    + ",\"hasUrl\":" + modes.contains(jakarta.servlet.SessionTrackingMode.URL) + "}";
        }
    }
}
