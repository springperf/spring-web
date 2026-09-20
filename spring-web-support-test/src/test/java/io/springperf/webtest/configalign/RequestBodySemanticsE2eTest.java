package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okio.BufferedSink;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 请求体/响应语义 E2E：chunked（未知长度）请求体、空 body @RequestBody → 400、
 * 缺 Content-Type → 415、204 No Content（ResponseEntity.noContent 与 void+@ResponseStatus）、
 * HEAD 作用于业务端点时抑制 body 且保留 Content-Length。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, RequestBodySemanticsE2eTest.BodyConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "server.error.whitelabel.enabled=false"
        })
class RequestBodySemanticsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON = MediaType.parse("application/json");

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @Test
    void chunkedRequestBody_parsed() throws Exception {
        // contentLength 未知（-1）→ OkHttp 以 Transfer-Encoding: chunked 发送
        RequestBody chunked = new RequestBody() {
            @Override
            public MediaType contentType() {
                return JSON;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public void writeTo(BufferedSink sink) throws IOException {
                sink.writeUtf8("{\"name\":\"chunked-e2e\"}");
            }
        };
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/json")).post(chunked).build()).execute();
        try {
            String body = resp.body().string();
            assertEquals(200, resp.code(), "chunked 请求体应正常聚合解析，body=" + body);
            assertEquals("chunked-e2e", body.trim());
        } finally {
            resp.close();
        }
    }

    @Test
    void emptyBody_requiredRequestBody_returns400() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/json"))
                .post(RequestBody.create(new byte[0], JSON)).build()).execute();
        try {
            assertEquals(400, resp.code(),
                    "必填 @RequestBody 但 body 为空应 400（HttpMessageNotReadable），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void missingContentType_returns415() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/json"))
                .post(RequestBody.create("{\"a\":1}".getBytes(), null)).build()).execute();
        try {
            int code = resp.code();
            assertEquals(true, code == 415 || code == 400,
                    "缺 Content-Type 的 @RequestBody 应 415（或 400），实际 " + code);
        } finally {
            resp.close();
        }
    }

    @Test
    void responseEntityNoContent_returns204WithEmptyBody() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/no-content")).get().build()).execute();
        try {
            assertEquals(204, resp.code());
            assertEquals(0, resp.body().bytes().length, "204 不应有响应体");
        } finally {
            resp.close();
        }
    }

    @Test
    void voidWithResponseStatus_returns204() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/void-204")).get().build()).execute();
        try {
            assertEquals(204, resp.code(), "void + @ResponseStatus(NO_CONTENT) 应 204");
        } finally {
            resp.close();
        }
    }

    @Test
    void headOnControllerEndpoint_suppressesBodyKeepsContentLength() throws Exception {
        long fullLength;
        okhttp3.Response full = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/payload")).get().build()).execute();
        try {
            assertEquals(200, full.code());
            fullLength = full.body().bytes().length;
        } finally {
            full.close();
        }

        okhttp3.Response head = CLIENT.newCall(new okhttp3.Request.Builder()
                .url(url("/e2e-body/payload")).head().build()).execute();
        try {
            assertEquals(200, head.code());
            assertEquals(0, head.body().bytes().length, "HEAD 不应返回 body");
            String contentLength = head.header("Content-Length");
            assertNotNull(contentLength, "HEAD 应保留 Content-Length");
            assertEquals(String.valueOf(fullLength), contentLength,
                    "HEAD 的 Content-Length 应与 GET 实际长度一致（RFC 7231 §4.3.2）");
        } finally {
            head.close();
        }
    }

    @TestConfiguration
    static class BodyConfig {
        @Bean
        BodyController bodyController() {
            return new BodyController();
        }
    }

    @RestController
    static class BodyController {

        @PostMapping(value = "/e2e-body/json", consumes = "application/json")
        public String json(@org.springframework.web.bind.annotation.RequestBody Map<String, Object> body) {
            Object name = body.get("name");
            return name != null ? name.toString() : "keys:" + body.size();
        }

        @GetMapping("/e2e-body/no-content")
        public ResponseEntity<Void> noContent() {
            return ResponseEntity.noContent().build();
        }

        @GetMapping("/e2e-body/void-204")
        @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
        public void void204() {
        }

        @GetMapping("/e2e-body/payload")
        public Map<String, String> payload() {
            return Map.of("data", "p".repeat(512));
        }
    }
}
