package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * gzip 响应压缩 E2E：启用 {@code server.compression.*} 后，验证大 JSON 被压缩且内容完整、
 * 小响应/非白名单 MIME 不压缩、零拷贝文件响应（writeFile）不被压缩且字节无损。
 */
@SpringBootTest(classes = TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.compression.enabled=true",
                "server.compression.min-response-size=2KB",
                "server.compression.excluded-user-agents=BadBot"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class CompressionE2ETest {

    @LocalServerPort
    protected int serverPort;

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    private String url(String path) {
        return "http://localhost:" + serverPort + "/api" + path;
    }

    private String bodyPlain(String path) throws IOException {
        Request req = new Request.Builder().url(url(path)).get().build();
        try (Response resp = client.newCall(req).execute()) {
            assertEquals(200, resp.code());
            return resp.body().string();
        }
    }

    private Response gzipRequest(String path, String userAgent) throws IOException {
        Request.Builder b = new Request.Builder().url(url(path))
                .header("Accept-Encoding", "gzip");
        if (userAgent != null) {
            b.header("User-Agent", userAgent);
        }
        return client.newCall(b.build()).execute();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(data));
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = gis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }

    @Test
    void largeJson_isGzipCompressed_andContentIntact() throws Exception {
        String plain = bodyPlain("/compression/json-large");
        assertTrue(plain.length() > 2000, "payload should be large");
        try (Response resp = gzipRequest("/compression/json-large", null)) {
            assertEquals(200, resp.code());
            assertEquals("gzip", resp.header("Content-Encoding"));
            byte[] decompressed = gunzip(resp.body().bytes());
            assertEquals(plain, new String(decompressed, StandardCharsets.UTF_8));
        }
    }

    @Test
    void smallJson_isNotCompressed() throws Exception {
        try (Response resp = gzipRequest("/compression/json-small", null)) {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "small body below min-response-size must not compress");
        }
    }

    @Test
    void binaryLarge_isNotCompressed_byMimeWhitelist() throws Exception {
        try (Response resp = gzipRequest("/compression/binary-large", null)) {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "application/octet-stream is not in the whitelist");
        }
    }

    @Test
    void downloadFile_isNotCompressed_andIntact() throws Exception {
        File f = new File("pom.xml");
        assertTrue(f.exists(), "test working dir should contain pom.xml");
        byte[] expected = Files.readAllBytes(f.toPath());
        try (Response resp = gzipRequest("/p1/download-file", null)) {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "writeFile (DefaultFileRegion) must skip compression");
            assertArrayEquals(expected, resp.body().bytes());
        }
    }

    @Test
    void excludedUserAgent_isNotCompressed() throws Exception {
        try (Response resp = gzipRequest("/compression/json-large", "BadBot/1.0")) {
            assertEquals(200, resp.code());
            assertNull(resp.header("Content-Encoding"), "BadBot matches excluded-user-agents");
        }
    }
}
