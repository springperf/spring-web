package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Multipart 落盘配置 E2E：{@code spring.servlet.multipart.file-size-threshold} 决定 part 在内存
 * 还是磁盘，{@code spring.servlet.multipart.location} 决定落盘目录。
 * <p>
 * 断言方式是**在处理过程中列举落盘目录**（Netty 的 DiskFileUpload 在解码阶段即建文件）， 从而区分「内存 part」与「磁盘 part」；请求结束后目录必须恢复为空，否则即为临时文件泄漏。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        MultipartDiskSpoolE2eTest.SpoolConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "spring.servlet.multipart.enabled=true",
                "spring.servlet.multipart.file-size-threshold=1024", "spring.servlet.multipart.max-file-size=1MB" })
class MultipartDiskSpoolE2eTest {

    /** 落盘目录（由 @DynamicPropertySource 在上下文启动前创建并注入）。 */
    static Path spoolDir;

    private static final int THRESHOLD = 1024;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(20)).writeTimeout(Duration.ofSeconds(20)).build();

    @DynamicPropertySource
    static void spoolLocation(DynamicPropertyRegistry registry) throws IOException {
        spoolDir = Path.of("target", "e2e-multipart-spool").toAbsolutePath();
        Files.createDirectories(spoolDir);
        // 清理上次运行残留
        try (Stream<Path> files = Files.list(spoolDir)) {
            files.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 残留文件不影响本次断言（每次断言都对比增量）
                }
            });
        }
        registry.add("spring.servlet.multipart.location", () -> spoolDir.toString());
    }

    @LocalServerPort
    int port;

    private static long spoolFileCount() throws IOException {
        try (Stream<Path> files = Files.list(spoolDir)) {
            return files.count();
        }
    }

    /**
     * 等待落盘目录文件数收敛到 {@code expected}：临时文件在请求释放（release）阶段才被清理， 紧跟响应之后立即枚举会与清理线程竞态，故按最终一致性断言（最多等 2s）。 若始终不收敛即为真实泄漏。
     */
    private static long awaitSpoolCount(long expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 2000;
        long count = safeSpoolCount();
        while (count != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            count = safeSpoolCount();
        }
        return count;
    }

    private static long safeSpoolCount() {
        try {
            return spoolFileCount();
        } catch (IOException e) {
            throw new IllegalStateException("无法枚举落盘目录 " + spoolDir, e);
        }
    }

    /** 上传单个文件，返回控制器观测到的 JSON。 */
    private String upload(int size, String fieldName) throws Exception {
        byte[] payload = new byte[size];
        Arrays.fill(payload, (byte) 'x');
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart(fieldName,
                "payload.bin", RequestBody.create(payload, MediaType.parse("application/octet-stream"))).build();
        Response resp = CLIENT
                .newCall(new Request.Builder().url("http://localhost:" + port + "/e2e-spool/upload").post(body).build())
                .execute();
        try {
            assertEquals(200, resp.code(), "上传应成功，实际 " + resp.code());
            return resp.body().string();
        } finally {
            resp.close();
        }
    }

    @Test
    void partAboveThreshold_spooledToConfiguredLocation_thenCleanedUp() throws Exception {
        long before = spoolFileCount();
        String body = upload(THRESHOLD * 8, "file");
        assertTrue(body.contains("\"size\":8192"), "文件内容应完整送达，实际 " + body);
        assertTrue(body.contains("\"spooledDuring\":1"), "超过阈值的 part 应在处理期间已落盘到配置目录，实际 " + body);

        // 请求结束后临时文件必须被清理（否则每次上传泄漏一个文件）；清理发生在请求释放阶段，故按最终一致性等待
        long after = awaitSpoolCount(before);
        assertEquals(before, after, "请求结束后落盘目录应恢复原状，实际 before=" + before + " after=" + after);
    }

    @Test
    void partBelowThreshold_keptInMemory_locationUntouched() throws Exception {
        long before = spoolFileCount();
        String body = upload(THRESHOLD / 4, "file");
        assertTrue(body.contains("\"size\":256"), "小文件内容应完整送达，实际 " + body);
        assertTrue(body.contains("\"spooledDuring\":0"), "低于阈值的 part 应留在内存（配置目录不得出现文件），实际 " + body);
        assertEquals(before, spoolFileCount(), "内存 part 不应产生临时文件");
    }

    @Test
    void multipleParts_mixedSizes_onlyLargePartSpooled() throws Exception {
        byte[] small = new byte[100];
        byte[] large = new byte[THRESHOLD * 4];
        Arrays.fill(small, (byte) 'a');
        Arrays.fill(large, (byte) 'b');
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("small", "small.bin",
                        RequestBody.create(small, MediaType.parse("application/octet-stream")))
                .addFormDataPart("large", "large.bin",
                        RequestBody.create(large, MediaType.parse("application/octet-stream")))
                .build();
        Response resp = CLIENT.newCall(
                new Request.Builder().url("http://localhost:" + port + "/e2e-spool/upload-two").post(body).build())
                .execute();
        try {
            assertEquals(200, resp.code());
            String json = resp.body().string();
            assertTrue(json.contains("\"smallSize\":100") && json.contains("\"largeSize\":4096"),
                    "两个 part 内容都应完整，实际 " + json);
            assertTrue(json.contains("\"spooledDuring\":1"), "同一请求中只有超过阈值的 part 落盘，实际 " + json);
        } finally {
            resp.close();
        }
        assertEquals(0, awaitSpoolCount(0), "请求结束后不应残留临时文件");
    }

    @Test
    void partExactlyAtThreshold_contentIntact() throws Exception {
        // 边界值：恰好等于阈值时的落盘与否取决于实现口径，此处只锁定「内容完整」这一硬契约
        String body = upload(THRESHOLD, "file");
        assertTrue(body.contains("\"size\":" + THRESHOLD), "阈值边界处内容必须完整，实际 " + body);
        assertEquals(0, awaitSpoolCount(0), "请求结束后不应残留临时文件");
    }

    @Test
    void maxFileSizeExceeded_rejected() throws Exception {
        byte[] payload = new byte[2 * 1024 * 1024];
        MultipartBody body = new MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("file", "big.bin",
                RequestBody.create(payload, MediaType.parse("application/octet-stream"))).build();
        Response resp = CLIENT
                .newCall(new Request.Builder().url("http://localhost:" + port + "/e2e-spool/upload").post(body).build())
                .execute();
        try {
            assertTrue(resp.code() >= 400, "超过 spring.servlet.multipart.max-file-size 应被拒绝，实际 " + resp.code());
        } finally {
            resp.close();
        }
        assertEquals(0, awaitSpoolCount(0), "被拒绝的上传也不应残留临时文件");
    }

    @TestConfiguration
    static class SpoolConfig {
        @Bean
        SpoolController spoolController() {
            return new SpoolController();
        }
    }

    @RestController
    static class SpoolController {

        @PostMapping("/e2e-spool/upload")
        public String upload(@RequestParam("file") MultipartFile file) throws IOException {
            byte[] content = file.getBytes();
            long spooled = countSpooledFiles();
            return "{\"size\":" + content.length + ",\"name\":\"" + file.getOriginalFilename() + "\",\"spooledDuring\":"
                    + spooled + "}";
        }

        @PostMapping("/e2e-spool/upload-two")
        public String uploadTwo(@RequestParam("small") MultipartFile small, @RequestParam("large") MultipartFile large)
                throws IOException {
            long spooled = countSpooledFiles();
            return "{\"smallSize\":" + small.getBytes().length + ",\"largeSize\":" + large.getBytes().length
                    + ",\"spooledDuring\":" + spooled + "}";
        }

        private static long countSpooledFiles() throws IOException {
            try (Stream<Path> files = Files.list(spoolDir)) {
                return files.count();
            }
        }
    }
}
