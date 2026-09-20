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
import org.springframework.validation.MessageCodesResolver;
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
 * 优先级 E2E：容器中存在自定义 {@link MessageCodesResolver} bean 时，**bean 优先于**
 * {@code spring.mvc.message-codes-resolver-format} 配置（属性只决定默认解析器）。
 * 本用例同时把属性设为 postfix，以证明结果是 bean 而非属性生效。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, MessageCodesCustomResolverE2eTest.CustomConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.mvc.message-codes-resolver-format=postfix_error_code"
        })
class MessageCodesCustomResolverE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Test
    void customResolverBean_takesPrecedenceOverProperty() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + "/e2e-mc3/validate")
                .post(okhttp3.RequestBody.create("{\"name\":\"\"}", MediaType.parse("application/json")))
                .build()).execute();
        try {
            assertEquals(400, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("CUSTOM:NotBlank"),
                    "自定义 MessageCodesResolver bean 应生效，实际 " + body);
            assertTrue(!body.contains("form.name.NotBlank"),
                    "自定义 bean 生效时不应再产出属性格式的错误码，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class CustomConfig {

        @Bean
        Validator codeFormValidator() {
            return new MessageCodesSupport.RejectingNameValidator();
        }

        @Bean
        MessageCodesResolver customMessageCodesResolver() {
            return new MessageCodesResolver() {
                @Override
                public String[] resolveMessageCodes(String errorCode, String objectName) {
                    return new String[]{"CUSTOM:" + errorCode};
                }

                @Override
                public String[] resolveMessageCodes(String errorCode, String objectName,
                                                    String field, Class<?> fieldType) {
                    return new String[]{"CUSTOM:" + errorCode};
                }
            };
        }

        @Bean
        CustomController customController() {
            return new CustomController();
        }

        @Bean
        CodesAdvice codesAdvice() {
            return new CodesAdvice();
        }
    }

    @RestController
    static class CustomController {
        @PostMapping("/e2e-mc3/validate")
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
