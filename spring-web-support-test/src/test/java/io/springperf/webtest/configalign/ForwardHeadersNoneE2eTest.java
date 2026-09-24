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
 * {@code server.forward-headers-strategy} 默认 NONE E2E：转发头默认不信任， 客户端伪造 X-Forwarded-Proto 不得改变请求 scheme（防伪造）。
 */
@SpringBootTest(classes = { io.springperf.webtest.SupportTestApplication.class,
        ForwardHeadersNoneE2eTest.SchemeEchoConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardHeadersNoneE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    @Test
    void forwardedProto_ignoredByDefault() throws Exception {
        okhttp3.Response resp = CLIENT
                .newCall(new okhttp3.Request.Builder().url("http://localhost:" + port + "/api/e2e-forward/scheme")
                        .header("X-Forwarded-Proto", "https").build())
                .execute();
        try {
            assertEquals("http", resp.body().string().trim(), "默认 NONE 策略不得信任 X-Forwarded-Proto（防客户端伪造 scheme）");
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
