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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.error.include-*}=on-param 的**参数门控** E2E（对齐 Boot {@code AbstractErrorController}）： 请求参数 {@code trace} /
 * {@code message} / {@code errors} 分别控制异常栈 / message / 绑定错误的暴露， 且取值 {@code false} 视为未命中（{@code ?trace=false} 不暴露）。
 * <p>
 * 重点是绑定错误：若 on-param 被实现成 always，用户以为「按需暴露」实则每次下发字段级校验信息， 属超出配置意图的信息外泄。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ErrorAttributesOnParamE2eTest.OnParamConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.error.include-message=on-param",
                "server.error.include-stacktrace=on-param", "server.error.include-binding-errors=on-param",
                "server.error.whitelabel.enabled=false" })
class ErrorAttributesOnParamE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String body(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        Response resp = CLIENT.newCall(b.build()).execute();
        try {
            return resp.code() + "|" + resp.body().string();
        } finally {
            resp.close();
        }
    }

    // ==================== message ====================

    @Test
    void message_hiddenWithoutParam() throws Exception {
        String body = body("/e2e-ea/send403");
        assertTrue(body.startsWith("403|"), "实际 " + body);
        assertFalse(body.contains("secret-msg"), "未命中 message 参数时不得暴露 message，实际 " + body);
    }

    @Test
    void message_exposedWithParam() throws Exception {
        String body = body("/e2e-ea/send403?message=true");
        assertTrue(body.contains("secret-msg"), "?message=true 应暴露 message，实际 " + body);
    }

    @Test
    void message_paramFalse_countsAsAbsent() throws Exception {
        // Boot 语义：?message=false 视为未命中（显式关闭）
        String body = body("/e2e-ea/send403?message=false");
        assertFalse(body.contains("secret-msg"), "?message=false 应视为未命中，实际 " + body);
    }

    @Test
    void message_paramWithoutValue_countsAsPresent() throws Exception {
        // ?message（无值）与 ?message=anything 都算命中（仅 "false" 例外）
        String body = body("/e2e-ea/send403?message");
        assertTrue(body.contains("secret-msg"), "?message（无值）应算命中，实际 " + body);
    }

    // ==================== stacktrace ====================

    @Test
    void stacktrace_hiddenWithoutParam() throws Exception {
        String body = body("/e2e-ea/boom");
        assertTrue(body.startsWith("500|"), "实际 " + body);
        assertFalse(body.contains("at io.springperf"), "未命中 trace 参数时不得暴露栈，实际 " + body);
    }

    @Test
    void stacktrace_exposedWithParam() throws Exception {
        String body = body("/e2e-ea/boom?trace=true");
        assertTrue(body.contains("EaOnParamBoom"), "?trace=true 应暴露异常栈，实际 " + body);
    }

    @Test
    void stacktrace_paramFalse_countsAsAbsent() throws Exception {
        String body = body("/e2e-ea/boom?trace=false");
        assertFalse(body.contains("at io.springperf"), "?trace=false 应视为未命中，实际 " + body);
    }

    // ==================== binding errors ====================

    @Test
    void bindingErrors_hiddenWithoutErrorsParam() throws Exception {
        String body = post("/e2e-ea/validate", "{\"name\":\"\"}");
        assertFalse(body.contains("must-not-be-blank"), "on-param 且未带 errors 参数时不得暴露绑定错误，实际 " + body);
    }

    @Test
    void bindingErrors_exposedWithErrorsParam() throws Exception {
        String body = post("/e2e-ea/validate?errors=true", "{\"name\":\"\"}");
        assertTrue(body.contains("must-not-be-blank"), "?errors=true 应暴露绑定错误，实际 " + body);
    }

    @Test
    void bindingErrors_errorsParamFalse_countsAsAbsent() throws Exception {
        String body = post("/e2e-ea/validate?errors=false", "{\"name\":\"\"}");
        assertFalse(body.contains("must-not-be-blank"), "?errors=false 应视为未命中，实际 " + body);
    }

    private String post(String path, String json) throws Exception {
        Response resp = CLIENT
                .newCall(new Request.Builder().url("http://localhost:" + port + path)
                        .post(okhttp3.RequestBody.create(json, okhttp3.MediaType.parse("application/json"))).build())
                .execute();
        try {
            return resp.code() + "|" + resp.body().string();
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class OnParamConfig {

        /** 无 jakarta.validation 实现时用自定义 Validator 触发字段错误（含 BindingResult）。 */
        @Bean
        Validator codeFormValidator() {
            return new MessageCodesSupport.RejectingNameValidator();
        }

        @Bean
        OnParamController onParamController() {
            return new OnParamController();
        }
    }

    @RestController
    static class OnParamController {

        @GetMapping("/e2e-ea/send403")
        public void send403(jakarta.servlet.http.HttpServletResponse response) throws Exception {
            response.sendError(403, "secret-msg");
        }

        @GetMapping("/e2e-ea/boom")
        public String boom() {
            throw new EaOnParamBoom("boom-onparam");
        }

        @PostMapping("/e2e-ea/validate")
        public String validate(@Validated @RequestBody MessageCodesSupport.CodeForm form) {
            return "unexpected-ok";
        }
    }

    /** 校验失败时字段错误消息码，用于断言绑定错误是否外露。 */
    static class EaOnParamBoom extends RuntimeException {
        EaOnParamBoom(String message) {
            super(message);
        }
    }
}
