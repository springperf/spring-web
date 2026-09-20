package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.accesslog.*} E2E：开启访问日志 + directory 落盘后，
 * 请求路径被写入 access*.log（真实 AccessLogWebFilter 链路）。
 */
@SpringBootTest(classes = {io.springperf.webtest.SupportTestApplication.class,
                AccessLogE2eTest.AccessLogConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.accesslog.enabled=true",
                "server.accesslog.directory=target/accesslog-e2e",
                "server.accesslog.rotate=false"
        })
class AccessLogE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    private static final Path LOG_DIR = Path.of("target", "accesslog-e2e");

    @LocalServerPort
    int port;

    private static void cleanLogDir() throws IOException {
        if (Files.exists(LOG_DIR)) {
            try (Stream<Path> walk = Files.walk(LOG_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private String readAllLogs() throws IOException {
        StringBuilder sb = new StringBuilder();
        if (Files.exists(LOG_DIR)) {
            try (Stream<Path> files = Files.list(LOG_DIR)) {
                files.filter(p -> p.getFileName().toString().endsWith(".log"))
                        .forEach(p -> {
                            try {
                                sb.append(Files.readString(p));
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
        return sb.toString();
    }

    @BeforeAll
    static void cleanOnce() throws IOException {
        cleanLogDir();
    }

    @Test
    void accessLog_fileContainsRequestPathAndStatus() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/api/e2e-accesslog/first").build()).execute();
        try {
            assertEquals(200, resp.code());
        } finally {
            resp.close();
        }
        // 落盘是异步缓冲，轮询等待
        String logs = waitForLog("first");
        assertTrue(logs.contains("/e2e-accesslog/first"), "访问日志应包含请求路径，实际:\n" + logs);
        assertTrue(logs.contains("200"), "访问日志应包含状态码，实际:\n" + logs);
    }

    @Test
    void accessLog_secondRequest_loggedToo() throws Exception {
        okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder()
                .url("http://localhost:" + port + "/api/e2e-accesslog/second").build()).execute();
        try {
            assertEquals(200, resp.code());
        } finally {
            resp.close();
        }
        String logs = waitForLog("second");
        assertTrue(logs.contains("/e2e-accesslog/second"), "实际:\n" + logs);
    }

    private String waitForLog(String marker) throws Exception {
        for (int i = 0; i < 30; i++) {
            String logs = readAllLogs();
            if (logs.contains(marker)) {
                return logs;
            }
            Thread.sleep(200);
        }
        return readAllLogs();
    }

    @TestConfiguration
    static class AccessLogConfig {
        @Bean
        AccessLogHitController accessLogHitController() {
            return new AccessLogHitController();
        }
    }

    @RestController
    static class AccessLogHitController {
        @GetMapping("/e2e-accesslog/first")
        public String first() {
            return "ok";
        }

        @GetMapping("/e2e-accesslog/second")
        public String second() {
            return "ok";
        }
    }
}
