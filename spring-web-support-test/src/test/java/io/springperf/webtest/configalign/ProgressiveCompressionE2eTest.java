package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.GzipSource;
import okio.BufferedSource;
import okio.Okio;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 渐进式输出 × 响应压缩组合 E2E（{@code server.compression.*}）： 分块流经压缩器后，逐帧 gzip 仍应还原出完整有序内容，且压缩头（Content-Encoding/Vary）
 * 语义不因流式提交而丢失。OkHttp 不自动解压非主 body 场景，这里手动用 GzipSource 解压。
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        ProgressiveCompressionE2eTest.Cfg.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.compression.enabled=true",
                "server.compression.min-response-size=1B", "server.compression.mime-types=application/json" })
class ProgressiveCompressionE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private Response get(String path) throws IOException {
        return CLIENT.newCall(
                new Request.Builder().url("http://localhost:" + port + path).header("Accept-Encoding", "gzip").build())
                .execute();
    }

    @Test
    void progressiveJson_compressedAndComplete() throws Exception {
        Response resp = get("/e2e-pc/staged");
        try {
            assertEquals(200, resp.code());
            assertEquals("gzip", resp.header("Content-Encoding"), "流式提交的响应同样应被压缩，实际 headers=" + resp.headers());
            assertNotNull(resp.header("Vary"), "压缩响应应带 Vary");
            String body = resp.header("Content-Encoding") != null ? unGzip(resp) : resp.body().string();
            assertEquals("{\"part\":1}{\"part\":2}", body, "逐帧压缩后内容应完整有序");
        } finally {
            resp.close();
        }
    }

    @Test
    void progressiveUnsupportedEncoding_passesThrough() throws Exception {
        // Accept-Encoding: br（框架不支持的编码）→ 不压缩直传，内容仍完整
        Response resp = CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + "/e2e-pc/staged")
                .header("Accept-Encoding", "br").build()).execute();
        try {
            assertEquals(200, resp.code());
            assertEquals(null, resp.header("Content-Encoding"), "不支持的编码不得压缩");
            assertEquals("{\"part\":1}{\"part\":2}", resp.body().string());
        } finally {
            resp.close();
        }
    }

    private static String unGzip(Response resp) throws IOException {
        BufferedSource src = Okio.buffer(new GzipSource(resp.body().source()));
        return src.readUtf8();
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        PcController pcController() {
            return new PcController();
        }
    }

    @RestController
    static class PcController {
        /** 两段 JSON 渐进写出（application/json 在压缩白名单内）。 */
        @GetMapping("/e2e-pc/staged")
        public void staged(HttpServletResponse response) throws Exception {
            response.setContentType("application/json");
            response.getWriter().write("{\"part\":1}");
            response.getWriter().flush();
            Thread.sleep(120);
            response.getWriter().write("{\"part\":2}");
            response.getWriter().flush();
        }
    }
}
