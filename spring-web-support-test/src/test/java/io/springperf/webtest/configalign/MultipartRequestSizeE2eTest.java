package io.springperf.webtest.configalign;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.servlet.multipart.max-request-size} E2E：整个 multipart 请求体超限 → 413 （与单文件上限 max-file-size 互补），未超限正常到达控制器。
 */
@SpringBootTest(classes = { io.springperf.webtest.SupportTestApplication.class,
        MultipartRequestSizeE2eTest.ReqSizeConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "spring.servlet.multipart.max-file-size=2KB", "spring.servlet.multipart.max-request-size=2KB" })
class MultipartRequestSizeE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + "/api" + path;
    }

    private okhttp3.Response upload(int fileCount, int bytesPerFile) throws Exception {
        MultipartBody.Builder builder = new MultipartBody.Builder().setType(MultipartBody.FORM);
        for (int i = 0; i < fileCount; i++) {
            builder.addFormDataPart("file" + i, "f" + i + ".bin",
                    RequestBody.create(new byte[bytesPerFile], MediaType.parse("application/octet-stream")));
        }
        return CLIENT
                .newCall(new okhttp3.Request.Builder().url(url("/e2e-reqsize/upload")).post(builder.build()).build())
                .execute();
    }

    @Test
    void totalWithinLimit_ok() throws Exception {
        okhttp3.Response resp = upload(1, 100);
        try {
            assertEquals(200, resp.code(), "单文件 100B 远小于 2KB 应正常，实际 " + resp.code() + " body=" + resp.body().string());
        } finally {
            resp.close();
        }
    }

    @Test
    void eachFileWithinLimit_totalExceeded_returns413() throws Exception {
        // 两个文件各 1.5KB：均低于单文件上限 2KB，但总量 3KB 超过 max-request-size=2KB
        okhttp3.Response resp = upload(2, 1536);
        try {
            assertEquals(413, resp.code(), "总量 3KB > 2KB 请求体上限应 413，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class ReqSizeConfig {
        @Bean
        ReqSizeController reqSizeController() {
            return new ReqSizeController();
        }
    }

    @RestController
    static class ReqSizeController {
        @PostMapping("/e2e-reqsize/upload")
        public Map<String, Object> upload(@RequestParam("file0") MultipartFile file) throws Exception {
            return Map.of("size", file.getSize());
        }
    }
}
