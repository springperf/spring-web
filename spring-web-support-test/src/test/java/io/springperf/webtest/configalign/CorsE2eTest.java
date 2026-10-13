package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CORS 深水区 E2E（经 {@code WebMvcConfigurer.addCorsMappings} 桥接到框架 CorsRegistry）：
 * <ul>
 * <li>预检（OPTIONS + Origin + ACRM）：允许 → 200 且带完整允许头；不允许的来源/方法/请求头 → 403 且无 ACAO；</li>
 * <li>普通请求：带 Origin → ACAO/Vary/Expose-Headers；不带 Origin → 不写 ACAO（但仍写 Vary，避免缓存串味）；</li>
 * <li>allowCredentials=true 时 ACAO 回显来源（规范禁止与 {@code *} 并用）；</li>
 * <li>{@code @CrossOrigin} 方法级配置；</li>
 * <li>错误响应上的 CORS：500（处理器已解析、CORS 先于调用生效）保留 CORS 头； 405（处理器解析阶段即失败）无 CORS 头——与 Spring MVC 行为一致。</li>
 * </ul>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        CorsE2eTest.CorsConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.servlet.context-path=/")
class CorsE2eTest {

    private static final String ALLOWED = "https://allowed.example";
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private Response send(Request request) throws Exception {
        return CLIENT.newCall(request).execute();
    }

    /**
     * Vary 是多条独立响应头（Origin / Access-Control-Request-Method / Access-Control-Request-Headers）， OkHttp 的 {@code header()}
     * 只返回最后一条，故此处合并全部同名头再判定。
     */
    private static String varyJoined(Response resp) {
        return String.join(",", resp.headers("Vary"));
    }

    private Response preflight(String path, String origin, String method, String reqHeaders) throws Exception {
        Request.Builder b = new Request.Builder().url("http://localhost:" + port + path).method("OPTIONS",
                RequestBody.create(new byte[0], null));
        if (origin != null) {
            b.header("Origin", origin);
        }
        if (method != null) {
            b.header("Access-Control-Request-Method", method);
        }
        if (reqHeaders != null) {
            b.header("Access-Control-Request-Headers", reqHeaders);
        }
        return send(b.build());
    }

    // ==================== 预检 ====================

    @Test
    void preflight_allowedOriginAndMethod_returns200WithFullCorsHeaders() throws Exception {
        Response resp = preflight("/e2e-cors/plain", ALLOWED, "GET", null);
        try {
            assertEquals(200, resp.code(), "允许的预检应 200，实际 " + resp.code());
            assertEquals(ALLOWED, resp.header("Access-Control-Allow-Origin"), "allowCredentials=true 时必须回显来源而非 *");
            String methods = resp.header("Access-Control-Allow-Methods");
            assertTrue(methods != null && methods.contains("GET"), "应声明允许方法，实际 " + methods);
            assertEquals("true", resp.header("Access-Control-Allow-Credentials"),
                    "allowCredentials(true) 应下发 ACAC=true");
            assertEquals("1800", resp.header("Access-Control-Max-Age"), "maxAge(1800) 应下发 Access-Control-Max-Age");
            String vary = varyJoined(resp);
            assertTrue(vary != null && vary.contains("Origin"), "预检响应应 Vary: Origin（缓存正确性），实际 " + vary);
            assertTrue(vary.contains("Access-Control-Request-Method"),
                    "预检响应应 Vary: Access-Control-Request-Method，实际 " + vary);
        } finally {
            resp.close();
        }
    }

    @Test
    void preflight_disallowedOrigin_rejected403WithoutAcao() throws Exception {
        Response resp = preflight("/e2e-cors/plain", "https://evil.example", "GET", null);
        try {
            assertEquals(403, resp.code(), "非允许来源的预检应被拒绝，实际 " + resp.code());
            assertNull(resp.header("Access-Control-Allow-Origin"), "被拒绝的预检不得下发 ACAO");
        } finally {
            resp.close();
        }
    }

    @Test
    void preflight_disallowedMethod_rejected403() throws Exception {
        // 注册允许 GET/POST/DELETE；PUT 不在其列
        Response resp = preflight("/e2e-cors/plain", ALLOWED, "PUT", null);
        try {
            assertEquals(403, resp.code(), "非允许方法的预检应被拒绝，实际 " + resp.code());
            assertNull(resp.header("Access-Control-Allow-Origin"));
        } finally {
            resp.close();
        }
    }

    @Test
    void preflight_disallowedHeader_rejected403() throws Exception {
        // 注册 allowedHeaders="X-Custom"；请求头 X-NotAllowed 不在其列
        Response resp = preflight("/e2e-cors/plain", ALLOWED, "GET", "X-NotAllowed");
        try {
            assertEquals(403, resp.code(), "非允许请求头的预检应被拒绝，实际 " + resp.code());
            assertNull(resp.header("Access-Control-Allow-Origin"));
        } finally {
            resp.close();
        }
    }

    @Test
    void preflight_allowedHeader_echoedInAllowHeaders() throws Exception {
        Response resp = preflight("/e2e-cors/plain", ALLOWED, "POST", "X-Custom");
        try {
            assertEquals(200, resp.code(), "允许的预检应 200，实际 " + resp.code());
            String allowHeaders = resp.header("Access-Control-Allow-Headers");
            assertTrue(allowHeaders != null && allowHeaders.toLowerCase().contains("x-custom"),
                    "应声明允许请求头，实际 " + allowHeaders);
        } finally {
            resp.close();
        }
    }

    // ==================== 普通请求 ====================

    @Test
    void simpleRequest_allowedOrigin_setsAcaoAndExposeHeaders() throws Exception {
        Response resp = send(new Request.Builder().url("http://localhost:" + port + "/e2e-cors/plain")
                .header("Origin", ALLOWED).get().build());
        try {
            assertEquals(200, resp.code());
            assertEquals(ALLOWED, resp.header("Access-Control-Allow-Origin"));
            assertEquals("X-Exposed", resp.header("Access-Control-Expose-Headers"),
                    "exposedHeaders 应下发 Access-Control-Expose-Headers");
            assertTrue(varyJoined(resp).contains("Origin"));
            assertEquals("plain-body", resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void simpleRequest_withoutOrigin_noAcao() throws Exception {
        Response resp = send(new Request.Builder().url("http://localhost:" + port + "/e2e-cors/plain").get().build());
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Access-Control-Allow-Origin"), "无 Origin 的请求不应下发 ACAO");
            // Vary 仍应存在：同一 URL 的响应随 Origin 变化，缓存必须按 Origin 区分
            assertTrue(varyJoined(resp).contains("Origin"), "无 Origin 时也应保留 Vary: Origin，实际 " + varyJoined(resp));
        } finally {
            resp.close();
        }
    }

    // ==================== @CrossOrigin ====================

    @Test
    void crossOriginAnnotation_ownPolicy_applied() throws Exception {
        Response resp = send(new Request.Builder().url("http://localhost:" + port + "/e2e-cors/annotated")
                .header("Origin", "https://annotation.example").get().build());
        try {
            assertEquals(200, resp.code());
            assertEquals("https://annotation.example", resp.header("Access-Control-Allow-Origin"),
                    "@CrossOrigin(origins=...) 应生效");
        } finally {
            resp.close();
        }
    }

    // ==================== 错误响应上的 CORS ====================

    @Test
    void errorResponse500_keepsCorsHeaders() throws Exception {
        Response resp = send(new Request.Builder().url("http://localhost:" + port + "/e2e-cors/boom")
                .header("Origin", ALLOWED).get().build());
        try {
            assertEquals(500, resp.code(), "处理器抛异常应 500，实际 " + resp.code());
            assertEquals(ALLOWED, resp.header("Access-Control-Allow-Origin"), "处理器已解析后抛异常：CORS 头必须保留，否则浏览器读不到错误详情");
        } finally {
            resp.close();
        }
    }

    @Test
    void methodMismatch405_hasNoCorsHeaders_aligningSpring() throws Exception {
        // 与 Spring MVC 一致：处理器解析阶段（CORS 之前）即判定方法不匹配，
        // 故 405 不带 CORS 头——浏览器将把该错误当作 CORS 失败。
        Response resp = send(new Request.Builder().url("http://localhost:" + port + "/e2e-cors/plain")
                .header("Origin", ALLOWED).delete().build());
        try {
            assertEquals(405, resp.code(), "GET-only 端点收到 DELETE 应 405，实际 " + resp.code());
            assertTrue(resp.header("Allow") != null && resp.header("Allow").contains("GET"),
                    "405 应带 Allow 头，实际 " + resp.header("Allow"));
            assertNull(resp.header("Access-Control-Allow-Origin"), "Spring 语义：405 在 CORS 处理前产生，不带 ACAO");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class CorsConfig implements WebMvcConfigurer {

        @Override
        public void addCorsMappings(CorsRegistry registry) {
            registry.addMapping("/e2e-cors/**").allowedOrigins(ALLOWED).allowedMethods("GET", "POST", "DELETE")
                    .allowedHeaders("X-Custom").exposedHeaders("X-Exposed").allowCredentials(true).maxAge(1800);
        }

        @Bean
        CorsController corsController() {
            return new CorsController();
        }
    }

    @RestController
    static class CorsController {

        @GetMapping("/e2e-cors/plain")
        public String plain() {
            return "plain-body";
        }

        @GetMapping("/e2e-cors/boom")
        public String boom() {
            throw new IllegalStateException("intentional failure");
        }

        @CrossOrigin(origins = "https://annotation.example")
        @GetMapping("/e2e-cors/annotated")
        public String annotated() {
            return "annotated-body";
        }

        @PostMapping(value = "/e2e-cors/consume", consumes = MediaType.APPLICATION_JSON_VALUE)
        public String consume() {
            return "consumed";
        }
    }
}
