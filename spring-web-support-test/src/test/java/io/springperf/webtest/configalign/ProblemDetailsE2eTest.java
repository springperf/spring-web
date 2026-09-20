package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.problemdetails.enabled=true} E2E：错误响应切换为
 * RFC 7807 application/problem+json（含 type/title/status 字段）。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class,
                ProblemDetailsE2eTest.PdConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.mvc.problemdetails.enabled=true"
        })
class ProblemDetailsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void exception_renderedAsProblemJson() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-pd/boom")).build()).execute();
        try {
            String contentType = resp.header("Content-Type", "");
            String body = resp.body().string();
            assertEquals(500, resp.code(), body);
            assertTrue(contentType.startsWith("application/problem+json"),
                    "problemdetails 开启后错误响应应为 RFC 7807，实际 " + contentType);
            assertTrue(body.contains("\"status\"") && body.contains("500"),
                    "problem+json 应含 status 字段，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void notFound_renderedAsProblemJson() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-pd/missing")).build()).execute();
        try {
            String contentType = resp.header("Content-Type", "");
            String body = resp.body().string();
            assertEquals(404, resp.code(), body);
            assertTrue(contentType.startsWith("application/problem+json"),
                    "404 也应渲染为 problem+json，实际 " + contentType + " body=" + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class PdConfig {
        @Bean
        PdController pdController() {
            return new PdController();
        }
    }

    @RestController
    static class PdController {
        @GetMapping("/e2e-pd/boom")
        public String boom() {
            // 自定义异常类型：避开宿主测试应用中既有 @ControllerAdvice
            throw new E2ePdBoomException("pd-boom");
        }
    }

    /** 测试专用异常：无既有 advice 匹配，落入框架 server.error 兜底渲染。 */
    static class E2ePdBoomException extends RuntimeException {
        E2ePdBoomException(String message) {
            super(message);
        }
    }
}
