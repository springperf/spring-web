package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.error.path} 自定义值 + RFC 7807 的组合 E2E：错误页路径会作为 problem+json 的
 * {@code instance} 暴露（这是该配置唯一可观测的落地位置）；同时覆盖 {@code detail}/{@code trace}/
 * {@code errors} 三个受策略控制的扩展字段在 problem+json 中的呈现。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ErrorPathProblemDetailsE2eTest.PdPathConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.mvc.problemdetails.enabled=true",
                "server.error.path=/custom-err",
                "server.error.include-message=always",
                "server.error.include-stacktrace=always",
                "server.error.include-binding-errors=always",
                "server.error.whitelabel.enabled=false"
        })
class ErrorPathProblemDetailsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).get().build())
                .execute();
    }

    @Test
    void errorResponse_isProblemJsonWithCustomInstance() throws Exception {
        Response resp = get("/e2e-pdp/boom");
        try {
            assertEquals(500, resp.code());
            assertTrue(resp.header("Content-Type", "").startsWith("application/problem+json"),
                    "实际 Content-Type=" + resp.header("Content-Type"));
            String body = resp.body().string();
            assertTrue(body.contains("\"instance\":\"/custom-err\""),
                    "server.error.path 应作为 problem+json 的 instance 暴露，实际 " + body);
            assertTrue(body.contains("\"title\""), "problem+json 应含 title，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void includeMessageAlways_messageAppearsAsDetail() throws Exception {
        Response resp = get("/e2e-pdp/boom");
        try {
            String body = resp.body().string();
            assertTrue(body.contains("\"detail\":\"pdp-boom-msg\""),
                    "include-message=always 时异常 message 应作为 detail，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void includeStacktraceAlways_traceExtensionPresent() throws Exception {
        Response resp = get("/e2e-pdp/boom");
        try {
            String body = resp.body().string();
            assertTrue(body.contains("\"trace\":"), "include-stacktrace=always 应附 trace，实际 " + body);
            assertTrue(body.contains("PdpBoomException"),
                    "trace 内容应含异常类名，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void validationFailure_problemJsonContainsErrorsBlock() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/e2e-pdp/validate")
                .post(okhttp3.RequestBody.create("{\"name\":\"\"}",
                        okhttp3.MediaType.parse("application/json")))
                .build()).execute();
        try {
            assertEquals(400, resp.code(), "校验失败应 400");
            String body = resp.body().string();
            assertTrue(body.contains("\"errors\":{"),
                    "include-binding-errors=always 时 problem+json 应含 errors 扩展块，实际 " + body);
            assertTrue(body.contains("must-not-be-blank"),
                    "errors 块应含字段错误消息，实际 " + body);
            assertTrue(body.contains("\"name\""), "errors 块应以字段名为键，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void notFound_alsoProblemJson() throws Exception {
        Response resp = get("/e2e-pdp/definitely-missing");
        try {
            assertEquals(404, resp.code());
            assertTrue(resp.header("Content-Type", "").startsWith("application/problem+json"),
                    "404 也应走 problem+json，实际 " + resp.header("Content-Type"));
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class PdPathConfig {

        @Bean
        Validator codeFormValidator() {
            return new MessageCodesSupport.RejectingNameValidator();
        }

        @Bean
        PdPathController pdPathController() {
            return new PdPathController();
        }
    }

    @RestController
    static class PdPathController {

        @GetMapping("/e2e-pdp/boom")
        public String boom() {
            throw new PdpBoomException("pdp-boom-msg");
        }

        @PostMapping("/e2e-pdp/validate")
        public String validate(@Validated @RequestBody MessageCodesSupport.CodeForm form) {
            return "unexpected-ok";
        }
    }

    static class PdpBoomException extends RuntimeException {
        PdpBoomException(String message) {
            super(message);
        }
    }
}
