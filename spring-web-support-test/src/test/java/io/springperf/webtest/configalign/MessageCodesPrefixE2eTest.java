package io.springperf.webtest.configalign;

import okhttp3.MediaType;
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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.message-codes-resolver-format} 默认值（{@code prefix_error_code}）E2E：
 * 校验错误码形如 {@code NotBlank.<对象>.<字段>}（错误码在前）。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, MessageCodesPrefixE2eTest.PrefixConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/")
class MessageCodesPrefixE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String codes() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/e2e-mc/validate")
                .post(okhttp3.RequestBody.create("{\"name\":\"\"}", MediaType.parse("application/json")))
                .build()).execute();
        try {
            assertEquals(400, resp.code(), "校验失败应 400");
            return resp.body().string();
        } finally {
            resp.close();
        }
    }

    @Test
    void defaultFormat_prefixErrorCodeApplied() throws Exception {
        String body = codes();
        assertTrue(body.contains("NotBlank.form.name"),
                "默认格式下错误码应为 NotBlank.<对象>.<字段>，实际 " + body);
        assertTrue(body.contains("NotBlank.name"),
                "应包含 errorCode.field 形式，实际 " + body);
        assertTrue(body.contains("|NotBlank\""),
                "错误码链末位应为裸错误码 NotBlank，实际 " + body);
    }

    @TestConfiguration
    static class PrefixConfig {
        @Bean
        Validator codeFormValidator() {
            return new MessageCodesSupport.RejectingNameValidator();
        }

        @Bean
        PrefixController prefixController() {
            return new PrefixController();
        }

        @Bean
        CodesAdvice codesAdvice() {
            return new CodesAdvice();
        }
    }

    @RestController
    static class PrefixController {
        @PostMapping("/e2e-mc/validate")
        public String validate(@Validated @RequestBody MessageCodesSupport.CodeForm form) {
            return "unexpected-ok";
        }
    }

    @RestControllerAdvice
    static class CodesAdvice {
        @ExceptionHandler(MethodArgumentNotValidException.class)
        @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
        public String handle(MethodArgumentNotValidException ex) {
            List<String> codes = new ArrayList<>();
            ex.getBindingResult().getFieldErrors()
                    .forEach(fe -> codes.add(String.join("|", fe.getCodes())));
            return "{\"codes\":[\"" + String.join("\",\"", codes) + "\"]}";
        }
    }
}
