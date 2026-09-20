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
 * {@code spring.mvc.message-codes-resolver-format=postfix_error_code} E2E：
 * 校验错误码形如 {@code <对象>.<字段>.NotBlank}（错误码在后），与 Spring Boot 同名配置一致。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, MessageCodesPostfixE2eTest.PostfixConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.mvc.message-codes-resolver-format=postfix_error_code"
        })
class MessageCodesPostfixE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String codes() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/e2e-mc2/validate")
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
    void postfixFormat_errorCodeAppended() throws Exception {
        String body = codes();
        assertTrue(body.contains("form.name.NotBlank"),
                "postfix 格式下错误码应为 <对象>.<字段>.NotBlank，实际 " + body);
        assertTrue(body.contains("name.NotBlank"),
                "应包含 field.errorCode 形式，实际 " + body);
        assertTrue(body.contains("|NotBlank\""),
                "错误码链末位应为裸错误码 NotBlank（两种格式下都存在），实际 " + body);
    }

    @Test
    void postfixFormat_doesNotUsePrefixOrder() throws Exception {
        String body = codes();
        assertTrue(!body.contains("NotBlank.form.name"),
                "postfix 格式不应出现前缀式错误码，实际 " + body);
    }

    @TestConfiguration
    static class PostfixConfig {
        @Bean
        Validator codeFormValidator() {
            return new MessageCodesSupport.RejectingNameValidator();
        }

        @Bean
        PostfixController postfixController() {
            return new PostfixController();
        }

        @Bean
        CodesAdvice codesAdvice() {
            return new CodesAdvice();
        }
    }

    @RestController
    static class PostfixController {
        @PostMapping("/e2e-mc2/validate")
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
