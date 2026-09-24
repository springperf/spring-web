package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.Locale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.web.locale-bind=false} E2E：框架完全不绑定 {@code LocaleContextHolder}—— 省掉每请求的上下文分配与 ThreadLocal set/remove。
 * <p>
 * 语义（对齐 Spring：holder 为空时的回退）：{@code getLocaleContext()} 返回 null， {@code getLocale()} 回退 JVM 默认 Locale。配置了
 * {@code spring.web.locale=zh_CN} 也不再生效（这正是"不关注 Locale"的含义）。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        LocaleBindDisabledE2eTest.BindEchoConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "spring.web.locale=zh_CN", "spring.web.locale-resolver=fixed",
                "spring.web.locale-bind=false" })
class LocaleBindDisabledE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    /** 回显「是否未绑定 | getLocale() 是否等于 JVM 默认」（与运行机器默认 Locale 无关）。 */
    private String echoBindState(String acceptLanguage) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/e2e-locale/bind-state");
        if (acceptLanguage != null) {
            builder.header("Accept-Language", acceptLanguage);
        }
        okhttp3.Response resp = CLIENT.newCall(builder.build()).execute();
        try {
            assertEquals(200, resp.code());
            return resp.body().string().trim();
        } finally {
            resp.close();
        }
    }

    @Test
    void bindDisabled_localeContextNotBound_fallsBackToJvmDefault() throws Exception {
        assertEquals("true|true", echoBindState(null), "关闭绑定时 getLocaleContext() 应为 null，getLocale() 回退 JVM 默认");
    }

    @Test
    void bindDisabled_ignoresAcceptLanguageAndConfiguredLocale() throws Exception {
        // 无论客户端送什么头，都不应产生绑定（也就不会按头解析）
        assertEquals("true|true", echoBindState("fr-CA,fr;q=0.9"));
    }

    @TestConfiguration
    static class BindEchoConfig {
        @Bean
        BindStateController bindStateController() {
            return new BindStateController();
        }
    }

    @RestController
    static class BindStateController {
        @GetMapping("/e2e-locale/bind-state")
        public String bindState() {
            boolean unbound = org.springframework.context.i18n.LocaleContextHolder.getLocaleContext() == null;
            boolean isJvmDefault = org.springframework.context.i18n.LocaleContextHolder.getLocale()
                    .equals(Locale.getDefault());
            return unbound + "|" + isJvmDefault;
        }
    }
}
