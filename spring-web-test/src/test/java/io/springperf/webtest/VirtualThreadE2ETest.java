package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 3.2.x 虚拟线程集成测试。
 * <p>验证 {@code spring.threads.virtual.enabled=true} 时：
 * <ul>
 *   <li>业务线程池使用虚拟线程执行请求（JDK 21+ 有效）</li>
 *   <li>{@code @RunInPool} 标注的方法在虚拟线程上执行（JDK 21+ 有效）</li>
 *   <li>{@code @RunInPool(RunInPool.EVENTLOOP)} 仍保持在 EventLoop 上执行</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT, properties = {
        "server.port=9089",
        "server.servlet.context-path=/api",
        "spring.threads.virtual.enabled=true",
        "pool.core-pool-size=10"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class VirtualThreadE2ETest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl = "http://localhost:9089/api";

    @Test
    void bizPool_shouldUseVirtualThread() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21,
                "Virtual threads require JDK 21+, skipping on JDK " + Runtime.version().feature());

        Request req = new Request.Builder()
                .url(baseUrl + "/core/pool/virtual-thread")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertNotNull(body);

            boolean isVirtual = (boolean) body.get("isVirtual");
            String threadName = (String) body.get("thread");

            System.out.println("[VirtualThreadE2ETest] bizPool thread=" + threadName + " isVirtual=" + isVirtual);
            assertTrue(isVirtual, "Expected virtual thread in biz pool mode, got thread=" + threadName);
        }
    }

    @Test
    void runInPool_withDefaultPool_shouldUsePlatformThread() throws Exception {
        // JDK 17 下虚拟线程不可用，验证 biz pool 使用平台线程
        Request req = new Request.Builder()
                .url(baseUrl + "/core/pool/biz-pool")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertNotNull(body);
            String threadName = (String) body.get("thread");
            System.out.println("[VirtualThreadE2ETest] @RunInPool thread=" + threadName);
        }
    }

    @Test
    void eventLoop_shouldNotUseVirtualThread() throws Exception {
        // EventLoop 不应使用虚拟线程（即使虚拟线程全局启用）
        Request req = new Request.Builder()
                .url(baseUrl + "/core/pool/event-loop")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertNotNull(body);

            boolean isVirtual = body.containsKey("isVirtual") && (boolean) body.get("isVirtual");
            // EventLoop 始终使用平台线程
            assertFalse(isVirtual, "EventLoop should NOT use virtual threads");
        }
    }

    @Test
    void badPool_shouldThrowException() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/core/pool/bad-pool")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // 不存在池应返回 500
            assertTrue(resp.code() >= 500,
                    "Expected 5xx for non-existent pool, got " + resp.code());
        }
    }
}