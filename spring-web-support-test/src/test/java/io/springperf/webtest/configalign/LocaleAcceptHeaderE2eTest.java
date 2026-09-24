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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.web.locale-resolver=accept-header}（默认）E2E： Locale 跟随请求 Accept-Language 头解析；无头时回退
 * {@code spring.web.locale}。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        LocaleAcceptHeaderE2eTest.LocaleEchoConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "spring.web.locale=zh_CN" })
class LocaleAcceptHeaderE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

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
    void locale_followsAcceptLanguage() throws Exception {
        String locale = echoLocale("fr-CA,fr;q=0.9");
        assertTrue(locale.startsWith("fr"), "accept-header 策略应按 Accept-Language 解析 Locale，实际 " + locale);
    }

    @Test
    void locale_fallsBackToConfiguredWithoutHeader() throws Exception {
        assertEquals("zh_CN", echoLocale(null), "无 Accept-Language 时应回退 spring.web.locale");
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
