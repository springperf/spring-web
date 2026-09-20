package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.web.locale} + {@code spring.web.locale-resolver=fixed} E2E：
 * 固定策略下 Locale 恒为配置值，忽略客户端 Accept-Language 头。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, LocaleFixedE2eTest.LocaleEchoConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.locale=zh_CN",
                "spring.web.locale-resolver=fixed"
        })
class LocaleFixedE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String echoLocale(String acceptLanguage) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/e2e-locale/echo");
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
    void fixedLocale_appliedWithoutHeader() throws Exception {
        assertEquals("zh_CN", echoLocale(null));
    }

    @Test
    void fixedLocale_ignoresClientHeader() throws Exception {
        assertEquals("zh_CN", echoLocale("en-US"),
                "fixed 策略下 Accept-Language 不应改变 Locale");
    }

    @TestConfiguration
    static class LocaleEchoConfig {
        @Bean
        LocaleEchoController localeEchoController() {
            return new LocaleEchoController();
        }
    }

    @RestController
    static class LocaleEchoController {
        @GetMapping("/e2e-locale/echo")
        public String echo() {
            return LocaleContextHolder.getLocale().toString();
        }
    }
}
