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
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code spring.servlet.multipart.enabled=false} E2E：multipart 解析关闭后， 管线不做聚合，getParts 返回空集合（请求体不消费为 part）。
 */
@SpringBootTest(classes = { io.springperf.webtest.SupportTestApplication.class,
        MultipartDisabledE2eTest.DisabledConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "spring.servlet.multipart.enabled=false")
class MultipartDisabledE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    @Test
    void multipartDisabled_partsNotParsed() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/api/e2e-mp-disabled")
                .post(new MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", "f.bin",
                                RequestBody.create(new byte[100], MediaType.parse("application/octet-stream")))
                        .build())
                .build()).execute();
        try {
            String body = resp.body().string();
            assertEquals(200, resp.code(), body);
            assertEquals("not-multipart", body.trim(),
                    "enabled=false 时管线不安装 multipart 聚合器，getParts 应报非 multipart 请求，实际 " + body);
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class DisabledConfig {
        @Bean
        DisabledMultipartController disabledMultipartController() {
            return new DisabledMultipartController();
        }
    }

    @RestController
    static class DisabledMultipartController {
        @PostMapping("/e2e-mp-disabled")
        public String parts(HttpServletRequest request) {
            try {
                return "parts=" + request.getParts().size();
            } catch (Exception e) {
                // 对齐 Boot/Tomcat：multipart 关闭后 getParts 报"非 multipart 请求"
                return "not-multipart";
            }
        }
    }
}
