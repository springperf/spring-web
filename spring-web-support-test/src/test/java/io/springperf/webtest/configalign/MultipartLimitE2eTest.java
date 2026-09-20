package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code spring.servlet.multipart.*} E2E（真实管线）：
 * max-file-size 超限走管线级 413（SupportMultipartAggregator），未超限正常到达控制器。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                MultipartLimitE2eTest.UploadConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.servlet.multipart.max-file-size=1KB")
class MultipartLimitE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        // support-test 的 application.properties 配置了 context-path=/api
        return "http://localhost:" + port + "/api" + path;
    }

    private okhttp3.Response upload(String fileName, int bytes) throws Exception {
        byte[] data = new byte[bytes];
        java.util.Arrays.fill(data, (byte) 'x');
        RequestBody fileBody = RequestBody.create(data, MediaType.parse("application/octet-stream"));
        okhttp3.Request req = new okhttp3.Request.Builder().url(url("/e2e-upload"))
                .post(new MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart("file", fileName, fileBody)
                        .build())
                .build();
        return CLIENT.newCall(req).execute();
    }

    @Test
    void withinLimit_uploadSucceeds() throws Exception {
        okhttp3.Response resp = upload("small.bin", 100);
        try {
            assertEquals(200, resp.code(), "100B < 1KB 阈值应正常上传，实际 "
                    + resp.code() + " body=" + resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void exceedingLimit_returns413() throws Exception {
        okhttp3.Response resp = upload("big.bin", 8 * 1024);
        try {
            assertEquals(413, resp.code(), "8KB 文件超 1KB 上限应返回 413，实际 "
                    + resp.code() + " body=" + resp.body().string());
        } finally {
            resp.close();
        }
    }

    /** 上传端点：回显文件字节数。 */
    @TestConfiguration
    static class UploadConfig {
        @Bean
        UploadController uploadController() {
            return new UploadController();
        }
    }

    @RestController
    static class UploadController {
        @PostMapping("/e2e-upload")
        public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws Exception {
            return Map.of("size", file.getSize());
        }
    }
}
