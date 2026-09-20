package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.web.resources.cache.cachecontrol.max-age} E2E：
 * Duration 风格配置（1h）映射为静态资源 Cache-Control 的 max-age=3600 秒。
 */
@SpringBootTest(classes = ConfigAlignTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true",
                "spring.web.resources.cache.cachecontrol.max-age=1h"
        })
class WebResourcesCacheControlE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Test
    void cacheControlMaxAge_oneHour_reflectedAs3600Seconds() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/e2e-note.txt").build()).execute();
        try {
            assertEquals(200, resp.code());
            String cacheControl = resp.header("Cache-Control");
            assertTrue(cacheControl != null && cacheControl.contains("max-age=3600"),
                    "1h 应解析为 max-age=3600，实际 Cache-Control=" + cacheControl);
        } finally {
            resp.close();
        }
    }
}
