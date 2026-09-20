package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.throw-exception-if-no-handler-found=true}（默认）E2E：
 * 404 以 {@link NoHandlerFoundException} 进入异常体系，可被 @ControllerAdvice 拦截定制。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                NoHandlerAdviceE2eTest.AdviceConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoHandlerAdviceE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    @Test
    void missingPath_routedToControllerAdvice() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/api/definitely-missing-advice-e2e")
                .build()).execute();
        try {
            String body = resp.body().string();
            assertEquals(404, resp.code(), body);
            assertTrue(body.contains("advice-caught-404"),
                    "NoHandlerFoundException 应被 @ControllerAdvice 拦截，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class AdviceConfig {

        @Bean
        MissingPathController missingPathController() {
            return new MissingPathController();
        }

        @Bean
        NoHandlerAdvice noHandlerAdvice() {
            return new NoHandlerAdvice();
        }
    }

    @RestController
    static class MissingPathController {
        @GetMapping("/e2e-nohandler/hit")
        public String hit() {
            return "ok";
        }
    }

    @RestControllerAdvice
    static class NoHandlerAdvice {
        // 框架将 404 包装为 ResponseStatusException(NOT_FOUND) 进入异常体系（见
        // DispatcherHandler.handleOnNoMatchMappingContext），advice 按状态码识别 404。
        // 通过 servlet response 显式设置状态码与写出 body（经 PerfHttpServletResponse 适配层）。
        @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
        public String handle(org.springframework.web.server.ResponseStatusException ex,
                             jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
            if (ex.getStatusCode().value() == 404) {
                response.setStatus(404);
                response.setContentType("text/plain;charset=UTF-8");
                java.io.PrintWriter writer = response.getWriter();
                writer.write("advice-caught-404");
                writer.flush();
                return null;
            }
            response.setStatus(ex.getStatusCode().value());
            return "advice-caught:" + ex.getStatusCode();
        }
    }
}
