package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.web.resources.*} 定制 E2E：static-locations 替换默认位置、
 * static-path-pattern 限定映射前缀——三者组合语义：仅 custom-static 位置、
 * 仅 /res/** 前缀可命中；add-mappings=false 见 {@link WebResourcesDisabledE2eTest}。
 */
@SpringBootTest(classes = ConfigAlignTestApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true",
                "spring.web.resources.static-locations=classpath:/custom-static/",
                "spring.mvc.static-path-pattern=/res/**"
        })
class WebResourcesMoreE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private int code(String path) throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url(path)).build()).execute();
        try {
            return resp.code();
        } finally {
            resp.close();
        }
    }

    @Test
    void customLocation_servedUnderPathPattern() throws Exception {
        assertEquals(200, code("/res/e2e-custom.txt"),
                "static-locations=custom-static + path-pattern=/res/** 应命中 /res/e2e-custom.txt");
    }

    @Test
    void defaultLocation_replaced_notServed() throws Exception {
        assertEquals(404, code("/res/e2e-note.txt"),
                "static-locations 显式配置后替换默认位置，classpath:/static/ 不再被扫描");
    }

    @Test
    void outsidePathPattern_notServed() throws Exception {
        assertEquals(404, code("/e2e-custom.txt"),
                "static-path-pattern=/res/** 时前缀外不命中");
    }
}
