package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异常 → HTTP 状态映射矩阵 E2E（对齐 Spring MVC DefaultHandlerExceptionResolver 语义）： 405 + Allow 头、415 媒体类型不支持、406 不可接受、400
 * 参数缺失/类型不匹配/报文不可读、 ResponseStatusException 指定状态、@ResponseStatus 注解异常、异常 cause 链 advice 选择。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ErrorMappingE2eTest.MappingConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.error.include-message=always",
                "server.error.whitelabel.enabled=false", "spring.mvc.throw-exception-if-no-handler-found=false" })
class ErrorMappingE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    private static final MediaType JSON = MediaType.parse("application/json");
    private static final MediaType TEXT = MediaType.parse("text/plain");

    @LocalServerPort
    int port;

    private okhttp3.Response call(okhttp3.Request request) throws Exception {
        return CLIENT.newCall(request).execute();
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // ==================== 405 ====================

    @Test
    void methodNotAllowed_returns405WithAllowHeader() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/only-get"))
                .post(okhttp3.RequestBody.create("x", TEXT)).build());
        try {
            assertEquals(405, resp.code(), "GET-only 端点收到 POST 应 405");
            String allow = resp.header("Allow");
            assertNotNull(allow, "405 应携带 Allow 头（Spring MVC 语义）");
            assertTrue(allow.contains("GET"), "Allow 应列出支持的 GET，实际 " + allow);
        } finally {
            resp.close();
        }
    }

    // ==================== 415 ====================

    @Test
    void unsupportedMediaType_returns415() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/consumes-json"))
                .post(okhttp3.RequestBody.create("plain-text", TEXT)).build());
        try {
            assertEquals(415, resp.code(), "consumes=application/json 的端点收到 text/plain 应 415，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void supportedMediaType_accepted() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/consumes-json"))
                .post(okhttp3.RequestBody.create("{\"k\":\"v\"}", JSON)).build());
        try {
            assertEquals(200, resp.code(), "正确媒体类型应正常处理");
        } finally {
            resp.close();
        }
    }

    // ==================== 406 ====================

    @Test
    void notAcceptable_returns406() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/produces-json"))
                .header("Accept", "application/xml").build());
        try {
            assertEquals(406, resp.code(),
                    "produces=application/json 且 Accept: application/xml 应 406，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== 400 家族 ====================

    @Test
    void missingRequiredParam_returns400() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/required")).build());
        try {
            assertEquals(400, resp.code(),
                    "缺少必填 @RequestParam 应 400（MissingServletRequestParameter），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void typeMismatch_returns400() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/int?id=abc")).build());
        try {
            String body = resp.body().string();
            assertEquals(400, resp.code(),
                    "int 参数收到非数字应 400（MethodArgumentTypeMismatch），实际 " + resp.code() + " body=" + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void malformedJsonBody_returns400() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/body"))
                .post(okhttp3.RequestBody.create("{not-json", JSON)).build());
        try {
            assertEquals(400, resp.code(), "畸形 JSON 报文应 400（HttpMessageNotReadable），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== 状态码来源 ====================

    @Test
    void responseStatusException_mapsStatusAndMessage() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/status-ex")).build());
        try {
            String body = resp.body().string();
            assertEquals(404, resp.code(), "ResponseStatusException(NOT_FOUND) 应映射 404，body=" + body);
            assertTrue(body.contains("e2e-custom-reason"), "include-message=always 时 reason 应外露，body=" + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void responseStatusAnnotation_mapsStatus() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/teapot")).build());
        try {
            assertEquals(418, resp.code(), "@ResponseStatus(I_AM_A_TEAPOT) 异常应映射 418，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    // ==================== cause 链 advice 选择 ====================

    @Test
    void nestedCause_matchedByCauseChainAdvice() throws Exception {
        okhttp3.Response resp = call(new okhttp3.Request.Builder().url(url("/e2e-map/nested")).build());
        try {
            String body = resp.body().string();
            assertEquals(200, resp.code(), "cause 链命中 advice 应正常返回，body=" + body);
            assertTrue(body.contains("advice-root-cause:root-cause-value"),
                    "advice 应按 cause 链选中最具体的 IllegalArgumentException，body=" + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class MappingConfig {
        @Bean
        MappingController mappingController() {
            return new MappingController();
        }

        @Bean
        MappingAdvice mappingAdvice() {
            return new MappingAdvice();
        }
    }

    @RestController
    static class MappingController {

        @GetMapping("/e2e-map/only-get")
        public String onlyGet() {
            return "get-only";
        }

        @PostMapping(value = "/e2e-map/consumes-json", consumes = "application/json")
        public String consumesJson(@RequestBody Map<String, Object> body) {
            return "consumed:" + body.size();
        }

        @GetMapping(value = "/e2e-map/produces-json", produces = "application/json")
        public Map<String, String> producesJson() {
            return Map.of("ok", "true");
        }

        @GetMapping("/e2e-map/required")
        public String required(@RequestParam("must") String must) {
            return must;
        }

        @GetMapping("/e2e-map/int")
        public String intParam(@RequestParam("id") int id) {
            return String.valueOf(id);
        }

        @PostMapping("/e2e-map/body")
        public String jsonBody(@RequestBody Map<String, Object> body) {
            return "size:" + body.size();
        }

        @GetMapping("/e2e-map/status-ex")
        public String statusEx() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "e2e-custom-reason");
        }

        @GetMapping("/e2e-map/teapot")
        public String teapot() {
            throw new TeapotException();
        }

        @GetMapping("/e2e-map/nested")
        public String nested() {
            throw new WrapperException(new CauseChainRootException("root-cause-value"));
        }
    }

    @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
    static class TeapotException extends RuntimeException {
    }

    static class WrapperException extends RuntimeException {
        WrapperException(Throwable cause) {
            super("wrapper", cause);
        }
    }

    /** cause 链终点异常：用自定义类型避免命中 NumberFormatException 等基础类型（保持断言精确）。 */
    static class CauseChainRootException extends RuntimeException {
        CauseChainRootException(String message) {
            super(message);
        }
    }

    @RestControllerAdvice
    static class MappingAdvice {
        @ExceptionHandler(CauseChainRootException.class)
        public ResponseEntity<String> handleRoot(CauseChainRootException ex) {
            return ResponseEntity.ok("advice-root-cause:" + ex.getMessage());
        }
    }
}
