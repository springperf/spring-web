package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配置对齐 E2E（真实 Netty 管线）：静态资源默认映射 + cache.period、dispatch 开关、
 * whitelabel 404、addViewControllers（redirect/status）、locale fixed。
 *
 * <p>这些能力的风险点在「配置 → 管线/注册表」的装配，单测（mock props）无法覆盖，
 * 必须走真实服务验证（KeepAliveHandler 装配回归即此类缺陷）。</p>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ConfigAlignmentE2eTest.VcAndLocaleConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true",
                "spring.web.resources.cache.period=1h",
                "spring.mvc.dispatch.trace=false",
                "spring.mvc.dispatch.options=false",
                "spring.web.locale=zh_CN",
                "spring.web.locale-resolver=fixed"
        })
class ConfigAlignmentE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Autowired
    VcAndLocaleConfig cfg;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ==================== spring.web.resources 默认映射 ====================

    @Test
    void staticResource_autoMapping_servedWithCacheHeader() throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url("/e2e-note.txt")).build();
        okhttp3.Response resp = CLIENT.newCall(req).execute();
        try {
            assertEquals(200, resp.code(), "add-mappings=true 应自动注册 /** → classpath:/static/ 映射");
            assertEquals("e2e-static-ok", resp.body().string().trim());
            String cacheControl = resp.header("Cache-Control");
            assertNotNull(cacheControl, "cache.period 应写入 Cache-Control");
            assertTrue(cacheControl.contains("max-age=3600"), "Cache-Control 实际值: " + cacheControl);
        } finally {
            resp.close();
        }
    }

    // ==================== spring.mvc.dispatch 开关 ====================

    @Test
    void traceDispatch_disabled_returns404() throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url("/user/1")).method("TRACE", null).build();
        okhttp3.Response resp = CLIENT.newCall(req).execute();
        try {
            assertEquals(404, resp.code(), "dispatch.trace=false 时 TRACE 不应分发到处理器");
        } finally {
            resp.close();
        }
    }

    @Test
    void optionsDispatch_disabled_nonPreflight_returns404() throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url("/user/1")).method("OPTIONS", null).build();
        okhttp3.Response resp = CLIENT.newCall(req).execute();
        try {
            assertEquals(404, resp.code(), "dispatch.options=false 时非预检 OPTIONS 不应分发");
        } finally {
            resp.close();
        }
    }

    // ==================== whitelabel 404 ====================

    @Test
    void unknownPath_whitelabel404_htmlBody() throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url("/definitely-missing-e2e")).build();
        okhttp3.Response resp = CLIENT.newCall(req).execute();
        try {
            assertEquals(404, resp.code());
            String contentType = resp.header("Content-Type", "");
            assertTrue(contentType.startsWith("text/html"),
                    "whitelabel 默认开启应为 HTML 错误页，实际 " + contentType);
            assertTrue(resp.body().string().contains("404"), "错误页应包含状态码");
        } finally {
            resp.close();
        }
    }

    // ==================== addViewControllers 桥接 ====================

    @Test
    void viewController_redirect_returns302WithLocation() throws Exception {
        // 禁止自动跟随：否则 OkHttp 跟到无 handler 的 /e2e-vc-target 后返回最终 404
        OkHttpClient noFollow = CLIENT.newBuilder().followRedirects(false).build();
        okhttp3.Request req = new okhttp3.Request.Builder().url(url("/e2e-vc-redirect")).build();
        okhttp3.Response resp = noFollow.newCall(req).execute();
        try {
            assertEquals(302, resp.code(), "addRedirectViewController 应桥接为 302 重定向");
            String location = resp.header("Location");
            assertNotNull(location);
            assertTrue(location.endsWith("/e2e-vc-target"), "Location 实际值: " + location);
            assertEquals("", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void statusController_returns204Empty() throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url("/e2e-vc-status")).build();
        okhttp3.Response resp = CLIENT.newCall(req).execute();
        try {
            assertEquals(204, resp.code(), "addStatusController 应桥接为纯状态码响应");
            assertEquals("", resp.body().string());
        } finally {
            resp.close();
        }
    }

    // ==================== spring.web.locale fixed ====================

    @Test
    void localeFixed_returnsConfiguredLocale() throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url("/e2e-locale")).header("Accept-Language", "fr-FR").build();
        okhttp3.Response resp = CLIENT.newCall(req).execute();
        try {
            assertEquals(200, resp.code());
            // fixed 策略忽略请求头，恒用 spring.web.locale
            assertEquals("zh_CN", resp.body().string().trim());
        } finally {
            resp.close();
        }
    }

    /** 视图控制器与 locale 回显端点。 */
    @TestConfiguration
    static class VcAndLocaleConfig {

        private final io.springperf.web.context.WebContext webContext;

        VcAndLocaleConfig(io.springperf.web.context.WebContext webContext) {
            this.webContext = webContext;
        }

        io.springperf.web.context.WebContext webContext() {
            return webContext;
        }

        @Bean
        WebMvcConfigurer viewControllerBridge() {
            return new WebMvcConfigurer() {
                @Override
                public void addViewControllers(ViewControllerRegistry registry) {
                    registry.addRedirectViewController("/e2e-vc-redirect", "/e2e-vc-target");
                    registry.addStatusController("/e2e-vc-status",
                            org.springframework.http.HttpStatus.NO_CONTENT);
                }
            };
        }

        @Bean
        LocaleEchoController localeEchoController() {
            return new LocaleEchoController();
        }
    }

    @RestController
    static class LocaleEchoController {
        /** 回显 LocaleContextHolder（DispatcherHandler initContextHolders 写入）。 */
        @GetMapping("/e2e-locale")
        public String locale() {
            return LocaleContextHolder.getLocale().toString();
        }
    }
}
