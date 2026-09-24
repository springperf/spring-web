package io.springperf.webtest.configalign;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.Charset;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.encoding.*} E2E：charset=GBK + force-request/force-response—— GBK 表单体参数正确解码、响应以 GBK
 * 编码写出（Content-Type 带charset、字节可按 GBK 还原）。
 */
@SpringBootTest(classes = { io.springperf.webtest.SupportTestApplication.class,
        ServletEncodingE2eTest.EncodingConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.encoding.charset=GBK", "server.servlet.encoding.force-request=true",
                "server.servlet.encoding.force-response=true" })
class ServletEncodingE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    @Test
    void gbkFormBody_paramDecodedCorrectly() throws Exception {
        // POST 表单："你好" 的 GBK 字节 0xC4 0xE3 0xBA 0xC3，百分号编码提交；
        // Content-Type 不带 charset——解码必须使用容器强制设置的请求编码（GBK）
        okhttp3.Response resp = CLIENT
                .newCall(
                        new okhttp3.Request.Builder().url(url("/e2e-enc/param"))
                                .post(okhttp3.RequestBody.create("v=%C4%E3%BA%C3",
                                        okhttp3.MediaType.parse("application/x-www-form-urlencoded")))
                                .build())
                .execute();
        try {
            String body = resp.body().string();
            assertEquals(200, resp.code(), body);
            assertEquals("你好", body.trim(), "force-request=GBK 时 GBK 编码表单参数应按容器编码解码，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @Test
    void requestCharacterEncoding_forcedToGbk() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder().url(url("/e2e-enc/req-charset")).build())
                .execute();
        try {
            assertEquals("GBK", resp.body().string().trim(), "force-request 应强制请求 characterEncoding");
        } finally {
            resp.close();
        }
    }

    @Test
    void forceResponse_writesGbkBytesWithCharsetHeader() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder().url(url("/e2e-enc/resp")).build())
                .execute();
        try {
            String contentType = resp.header("Content-Type", "");
            assertTrue(contentType.contains("GBK"), "force-response 应在 Content-Type 写出 GBK charset，实际 " + contentType);
            byte[] bytes = resp.body().bytes();
            assertEquals("你好响应", new String(bytes, Charset.forName("GBK")), "响应体应按 GBK 编码写出");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class EncodingConfig {
        @Bean
        EncodingController encodingController() {
            return new EncodingController();
        }
    }

    @RestController
    static class EncodingController {

        @GetMapping("/e2e-enc/param")
        public String paramGet(@RequestParam("v") String v) {
            return v;
        }

        @PostMapping("/e2e-enc/param")
        public String paramPost(@RequestParam("v") String v) {
            return v;
        }

        @GetMapping("/e2e-enc/req-charset")
        public String reqCharset(HttpServletRequest request) {
            return request.getCharacterEncoding();
        }

        @GetMapping("/e2e-enc/resp")
        public String resp() {
            return "你好响应";
        }
    }
}
