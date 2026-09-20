package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.view.prefix/suffix} E2E：控制器返回 {@code jsp:hi}，
 * 由 prefix + name + suffix 拼出 {@code /e2e-jsp/hi.jsp} 并经 Jasper 渲染——
 * 自定义前缀/后缀真实生效（默认值为 /jsp/ 与 .jsp）。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ViewPrefixSuffixE2eTest.ViewConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.mvc.view.prefix=/e2e-jsp/",
                "spring.mvc.view.suffix=.jsp"
        })
class ViewPrefixSuffixE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Test
    void customPrefixSuffix_resolvesJspView() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/e2e-view/hello").build()).execute();
        try {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("e2e-jsp-hi"),
                    "jsp:hi 应按 prefix=/e2e-jsp/ + suffix=.jsp 解析为 /e2e-jsp/hi.jsp 并渲染，实际 body=" + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class ViewConfig {
        @Bean
        BareViewController bareViewController() {
            return new BareViewController();
        }
    }

    @Controller
    static class BareViewController {
        @GetMapping("/e2e-view/hello")
        public String hello() {
            return "jsp:hi";
        }
    }
}
