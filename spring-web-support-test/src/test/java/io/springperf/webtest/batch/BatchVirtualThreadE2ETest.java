package io.springperf.webtest.batch;

import io.springperf.webtest.SupportTestApplication;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 开启虚拟线程时（{@code spring.threads.virtual.enabled=true} + JDK 21+）， {@code @BatchMapping} 的批量方法应由虚拟线程执行 —— 与 default
 * 业务池的虚拟线程语义一致。
 */
@SpringBootTest(classes = SupportTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/api", "spring.threads.virtual.enabled=true" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BatchVirtualThreadE2ETest {

    @LocalServerPort
    private int serverPort;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).build();

    @Test
    void batchHandler_runsOnVirtualThread() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "虚拟线程需要 JDK 21+，当前 JDK " + Runtime.version().feature());

        Request req = new Request.Builder().url("http://localhost:" + serverPort + "/api/batch/thread?msg=hello").get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.startsWith("thread=batch-virtual-"), "批量方法应在虚拟线程上执行: " + body);
            assertTrue(body.endsWith(":virtual=true"), "执行线程应为虚拟线程: " + body);
        }
    }
}
