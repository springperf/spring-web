package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 响应压缩协商 E2E（{@code server.compression.*}）：min-response-size 阈值边界、 mime-types 白名单、Vary: Accept-Encoding、不支持编码（br）不压缩。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ContentNegotiationE2eTest.CompressionConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.compression.enabled=true",
                "server.compression.min-response-size=1KB", "server.compression.mime-types=application/json" })
class ContentNegotiationE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private okhttp3.Response get(String path, String... headers) throws Exception {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url("http://localhost:" + port + path);
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(builder.build()).execute();
    }

    @Test
    void aboveThreshold_whitelistedMime_compressedWithVary() throws Exception {
        okhttp3.Response resp = get("/e2e-cn/json-large", "Accept-Encoding", "gzip");
        try {
            assertEquals(200, resp.code());
            assertEquals("gzip", resp.header("Content-Encoding"), "超过 min-response-size 的白名单 MIME 应 gzip 压缩");
            String vary = resp.header("Vary");
            assertNotNull(vary, "压缩响应应带 Vary: Accept-Encoding（RFC 7231 §7.1.4）");
            // 头名大小写不敏感（HTTP 规范），按不敏感比对
            assertTrue(vary.toLowerCase(java.util.Locale.ROOT).contains("accept-encoding"), "实际 Vary=" + vary);
        } finally {
            resp.close();
        }
    }

    @Test
    void belowThreshold_notCompressed() throws Exception {
        okhttp3.Response resp = get("/e2e-cn/json-small", "Accept-Encoding", "gzip");
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "小于 min-response-size 的响应不应压缩");
        } finally {
            resp.close();
        }
    }

    @Test
    void nonWhitelistedMime_notCompressed() throws Exception {
        okhttp3.Response resp = get("/e2e-cn/text-large", "Accept-Encoding", "gzip");
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "mime-types 白名单之外的 text/plain 不压缩");
        } finally {
            resp.close();
        }
    }

    @Test
    void unsupportedEncoding_br_notCompressed() throws Exception {
        okhttp3.Response resp = get("/e2e-cn/json-large", "Accept-Encoding", "br");
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "不支持的 br 编码不应返回压缩响应");
        } finally {
            resp.close();
        }
    }

    @Test
    void noAcceptEncoding_notCompressed() throws Exception {
        okhttp3.Response resp = get("/e2e-cn/json-large", "Accept-Encoding", "identity");
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "未声明 gzip 时不应压缩");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class CompressionConfig {
        @Bean
        CnController cnController() {
            return new CnController();
        }
    }

    @RestController
    static class CnController {

        @GetMapping(value = "/e2e-cn/json-large", produces = "application/json")
        public Map<String, String> jsonLarge() {
            return Map.of("payload", "j".repeat(4096));
        }

        @GetMapping(value = "/e2e-cn/json-small", produces = "application/json")
        public Map<String, String> jsonSmall() {
            return Map.of("payload", "s".repeat(64));
        }

        @GetMapping(value = "/e2e-cn/text-large", produces = "text/plain")
        public String textLarge() {
            return "t".repeat(4096);
        }
    }
}
