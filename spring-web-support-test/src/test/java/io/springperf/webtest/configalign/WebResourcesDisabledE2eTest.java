package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.web.resources.add-mappings=false} E2E：不注册默认静态资源映射， classpath:/static/ 下的资源返回 404。
 */
@SpringBootTest(classes = ConfigAlignTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/", "spring.web.resources.add-mappings=false" })
class WebResourcesDisabledE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    @Test
    void addMappingsFalse_staticResourceReturns404() throws Exception {
        okhttp3.Response resp = CLIENT
                .newCall(new okhttp3.Request.Builder().url("http://localhost:" + port + "/e2e-note.txt").build())
                .execute();
        try {
            assertEquals(404, resp.code(), "add-mappings=false 时不注册默认映射，静态资源应 404");
        } finally {
            resp.close();
        }
    }
}
