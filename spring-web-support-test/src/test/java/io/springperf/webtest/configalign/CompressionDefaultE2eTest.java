package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code server.compression.enabled} 默认 false E2E：即使客户端携带
 * Accept-Encoding: gzip，大响应也不压缩（默认零开销）。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                CompressionDefaultE2eTest.LargeJsonConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompressionDefaultE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Test
    void compressionDisabledByDefault_largeResponseNotCompressed() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/api/e2e-compression/large")
                .header("Accept-Encoding", "gzip")
                .build()).execute();
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"),
                    "未开启 server.compression.enabled 时大响应也不应压缩，实际 "
                            + resp.header("Content-Encoding"));
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class LargeJsonConfig {
        @Bean
        LargeJsonController largeJsonController() {
            return new LargeJsonController();
        }
    }

    @org.springframework.web.bind.annotation.RestController
    static class LargeJsonController {
        @org.springframework.web.bind.annotation.GetMapping("/e2e-compression/large")
        public java.util.Map<String, String> large() {
            // ~4KB JSON，远超常见 min-response-size 阈值
            return java.util.Map.of("payload", "x".repeat(4096));
        }
    }
}
