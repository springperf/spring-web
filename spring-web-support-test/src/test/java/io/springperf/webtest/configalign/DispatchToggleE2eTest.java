package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.mvc.dispatch.trace/options=false} E2E（真实管线）：
 * TRACE 请求 404；非预检 OPTIONS 404；CORS 预检仍被框架处理（200 + CORS 头）——
 * 预检是框架级能力，不受用户路由分发开关影响。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, DispatchToggleE2eTest.CorsConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.mvc.dispatch.trace=false",
                "spring.mvc.dispatch.options=false"
        })
class DispatchToggleE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private okhttp3.Response send(String method, String... headers) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/e2e-dispatch/hit");
        if (method.equals("GET")) {
            builder.get();
        } else {
            builder.method(method, okhttp3.RequestBody.create(new byte[0], null));
        }
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(builder.build()).execute();
    }

    /** 对照：端点本身存活（GET 200）——保证后续 404 断言归因于分发开关而非端点缺失。 */
    @Test
    void getBaseline_endpointAlive() throws Exception {
        okhttp3.Response resp = send("GET");
        try {
            assertEquals(200, resp.code(), "GET 基线应 200，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void traceDisabled_returns404() throws Exception {
        okhttp3.Response resp = send("TRACE");
        try {
            assertEquals(404, resp.code(),
                    "dispatch.trace=false 时 TRACE 应 404（端点存在，见 getBaseline），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void optionsDisabled_nonPreflight_returns404() throws Exception {
        okhttp3.Response resp = send("OPTIONS");
        try {
            assertEquals(404, resp.code(),
                    "dispatch.options=false 时非预检 OPTIONS 应 404（端点存在，见 getBaseline），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void optionsDisabled_preflightStillHandled() throws Exception {
        okhttp3.Response resp = send("OPTIONS",
                "Origin", "https://example.com",
                "Access-Control-Request-Method", "GET");
        try {
            assertEquals(200, resp.code(),
                    "CORS 预检不受 OPTIONS 分发开关影响，实际 " + resp.code());
            assertTrue(resp.header("Access-Control-Allow-Origin") != null,
                    "预检响应应带 CORS 头，实际 headers=" + resp.headers());
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class CorsConfig {
        /** 框架原生 CorsRegistration 组件：自动注册进 CorsRegistry（供预检分支处理）。 */
        @Bean
        io.springperf.web.core.cors.CorsRegistration e2eCorsRegistration() {
            return new io.springperf.web.core.cors.CorsRegistration("/**")
                    .allowedOrigins("https://example.com")
                    .allowedMethods("GET", "POST");
        }

        /** 端点必须显式声明为 bean（嵌套静态类不会被自动装配注册） */
        @Bean
        DispatchHitController dispatchHitController() {
            return new DispatchHitController();
        }
    }

    @RestController
    static class DispatchHitController {
        @GetMapping("/e2e-dispatch/hit")
        public String hit() {
            return "ok";
        }
    }
}
