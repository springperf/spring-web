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
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code server.forward-headers-strategy=FRAMEWORK} E2E：
 * RFC 7239 Forwarded 与 X-Forwarded-Proto 头被信任并反映到请求 scheme；
 * 默认 NONE 策略下转发头被忽略（见 {@link ForwardHeadersNoneE2eTest}）。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                ForwardHeadersE2eTest.SchemeEchoConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.forward-headers-strategy=FRAMEWORK")
class ForwardHeadersE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    @Test
    void xForwardedProto_reflected() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-forward/scheme"))
                .header("X-Forwarded-Proto", "https")
                .build()).execute();
        try {
            assertEquals("https", resp.body().string().trim(),
                    "FRAMEWORK 策略应信任 X-Forwarded-Proto");
        } finally {
            resp.close();
        }
    }

    @Test
    void rfc7239Forwarded_reflected() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-forward/scheme"))
                .header("Forwarded", "for=192.0.2.60;proto=https;host=example.com")
                .build()).execute();
        try {
            assertEquals("https", resp.body().string().trim(),
                    "FRAMEWORK 策略应信任 RFC 7239 Forwarded（proto 优先级最高）");
        } finally {
            resp.close();
        }
    }

    @Test
    void noForwardHeader_fallsBackHttp() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-forward/scheme"))
                .build()).execute();
        try {
            assertEquals("http", resp.body().string().trim(),
                    "无转发头时兜底 http（明文 Netty 管线）");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class SchemeEchoConfig {
        @Bean
        SchemeEchoController schemeEchoController() {
            return new SchemeEchoController();
        }
    }

    @RestController
    static class SchemeEchoController {
        @GetMapping("/e2e-forward/scheme")
        public String scheme(HttpServletRequest request) {
            return request.getScheme();
        }
    }
}
