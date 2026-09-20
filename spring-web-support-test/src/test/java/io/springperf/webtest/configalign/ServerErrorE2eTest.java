package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.error.*} E2E：include-message=always 暴露 sendError/异常 message、
 * include-stacktrace=on-param 由 ?trace 参数控制栈暴露、whitelabel.enabled=false 关闭 HTML 错误页。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class,
                ServerErrorE2eTest.ErrConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.error.include-message=always",
                "server.error.include-stacktrace=on-param",
                "server.error.whitelabel.enabled=false"
        })
class ServerErrorE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        // ConfigAlignTestApp：context-path=/（隔离宿主应用的 @ControllerAdvice 干扰）
        return "http://localhost:" + port + path;
    }

    private String body(String path) throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url(path)).build()).execute();
        try {
            return resp.code() + "|" + resp.body().string();
        } finally {
            resp.close();
        }
    }

    @Test
    void sendMessage_included_whenAlways() throws Exception {
        String body = body("/e2e-err/send403");
        assertTrue(body.startsWith("403|"), "应返回 403，实际 " + body);
        assertTrue(body.contains("custom-msg-e2e"),
                "include-message=always 时 sendError 的 message 应外露，实际 " + body);
    }

    @Test
    void exceptionMessage_included_whenAlways() throws Exception {
        String body = body("/e2e-err/boom");
        assertTrue(body.startsWith("500|"), "应返回 500，实际 " + body);
        // 兜底渲染的 message 字段受 include-message 策略控制（always 时暴露，never 时隐藏）
        assertTrue(body.contains("\"message\""),
                "include-message=always 时错误体应含 message 字段，实际 " + body);
    }

    @Test
    void stacktrace_onParam_traceTrue_shows() throws Exception {
        String body = body("/e2e-err/boom?trace=true");
        assertTrue(body.contains("E2eBoomException"),
                "include-stacktrace=on-param 且 ?trace=true 应暴露异常栈，实际 " + body);
    }

    @Test
    void stacktrace_onParam_absent_hidden() throws Exception {
        String body = body("/e2e-err/boom");
        assertFalse(body.contains("at io.springperf"),
                "未带 ?trace=true 时不应暴露栈帧，实际 " + body);
    }

    @Test
    void whitelabelDisabled_errorBody_notHtmlPage() throws Exception {
        String body = body("/definitely-missing-e2e-err");
        assertTrue(body.startsWith("404|"), "应返回 404，实际 " + body);
        assertFalse(body.contains("<html") && body.contains("whitelabel"),
                "whitelabel.enabled=false 时不渲染 HTML whitelabel 页，实际 " + body);
    }

    @TestConfiguration
    static class ErrConfig {
        @Bean
        ErrController errController() {
            return new ErrController();
        }
    }

    @RestController
    static class ErrController {

        @GetMapping("/e2e-err/send403")
        public void send403(HttpServletResponse response) throws Exception {
            response.sendError(403, "custom-msg-e2e");
        }

        @GetMapping("/e2e-err/boom")
        public String boom() {
            // 自定义异常类型：避开宿主测试应用中既有 @ControllerAdvice（IllegalStateException 等）
            throw new E2eBoomException("boom-msg-e2e");
        }
    }

    /** 测试专用异常：无既有 advice 匹配，落入框架 server.error 兜底渲染。 */
    static class E2eBoomException extends RuntimeException {
        E2eBoomException(String message) {
            super(message);
        }
    }
}
