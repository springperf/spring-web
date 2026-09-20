package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.async.request-timeout} E2E：超时未完成的 DeferredResult
 * 由框架定时器回收为 504；及时完成则正常 200。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                AsyncTimeoutE2eTest.AsyncConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.mvc.async.request-timeout=500ms")
class AsyncTimeoutE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    @Test
    void deferredResult_neverCompleted_timesOut() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-async/never")).build()).execute();
        try {
            int code = resp.code();
            // Spring MVC 语义：异步超时经 AsyncRequestTimeoutException 映射为 503；
            // 框架默认超时兜底为 504。两者均表示「异步请求超时回收」。
            assertTrue(code == 503 || code == 504,
                    "500ms 超时未完成的 DeferredResult 应被框架回收（503/504），实际 " + code);
        } finally {
            resp.close();
        }
    }

    @Test
    void deferredResult_completedInTime_returns200() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-async/now")).build()).execute();
        try {
            assertEquals(200, resp.code());
            assertEquals("async-done", resp.body().string().trim());
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class AsyncConfig {
        @Bean
        AsyncController asyncController() {
            return new AsyncController();
        }
    }

    @RestController
    static class AsyncController {

        @GetMapping("/e2e-async/never")
        public DeferredResult<String> never() {
            return new DeferredResult<>();
        }

        @GetMapping("/e2e-async/now")
        public DeferredResult<String> now() {
            DeferredResult<String> result = new DeferredResult<>();
            result.setResult("async-done");
            return result;
        }
    }
}
