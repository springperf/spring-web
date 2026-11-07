package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledForJreRange;
import org.junit.jupiter.api.condition.EnabledForJreRange;
import org.junit.jupiter.api.condition.JRE;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 3.2.x 虚拟线程集成测试。
 * <p>
 * 验证 {@code spring.threads.virtual.enabled=true} 时：
 * <ul>
 * <li>业务线程池使用虚拟线程执行请求（JDK 21+ 有效）</li>
 * <li>{@code @RunInPool} 标注的方法在虚拟线程上执行（JDK 21+ 有效）</li>
 * <li>{@code @RunInPool(RunInPool.EVENTLOOP)} 仍保持在 EventLoop 上执行</li>
 * </ul>
 * </p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/api", "spring.threads.virtual.enabled=true", "pool.core-pool-size=10" })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class VirtualThreadE2ETest {

    @org.springframework.boot.test.web.server.LocalServerPort
    private int serverPort;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10)).build();

    private String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    private String baseUrl() {
        return url("/api");
    }

    @Test
    @EnabledForJreRange(min = JRE.JAVA_21)
    void bizPool_shouldUseVirtualThread() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/pool/virtual-thread").get().build();
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
    @DisabledForJreRange(min = JRE.JAVA_21)
    void runInPool_withDefaultPool_shouldUsePlatformThread() throws Exception {
        // 本用例仅在虚拟线程不可用的 JDK（<21）上有效：类级属性 spring.threads.virtual.enabled=true
        // 在 JDK 21+ 会令默认业务池使用虚拟线程（perf-virtual-），与"默认池应使用平台线程"矛盾。
        // JDK 21+ 由 bizPool_shouldUseVirtualThread 覆盖虚拟线程语义。
        Request req = new Request.Builder().url(baseUrl() + "/core/pool/biz-pool").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertNotNull(body);
            String threadName = (String) body.get("thread");
            assertNotNull(threadName);
            assertFalse(threadName.startsWith("perf-virtual-"), "@RunInPool 默认池应使用平台线程，实际线程名: " + threadName);
        }
    }

    @Test
    void eventLoop_shouldNotUseVirtualThread() throws Exception {
        // EventLoop 不应使用虚拟线程（即使虚拟线程全局启用）
        Request req = new Request.Builder().url(baseUrl() + "/core/pool/event-loop").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertNotNull(body);

            // 不能用 containsKey && get 的短路写法：字段缺失时结果为 false，assertFalse 恒通过
            assertTrue(body.containsKey("isVirtual"), "端点必须返回 isVirtual 字段，实际: " + body);
            assertEquals(false, body.get("isVirtual"), "EventLoop 始终使用平台线程，实际: " + body);
            assertNotNull(body.get("thread"), "端点应返回 thread 字段，实际: " + body);
        }
    }

    @Test
    void badPool_shouldThrowException() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/core/pool/bad-pool").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // 不存在池应返回 500
            assertEquals(500, resp.code(), "不存在的线程池应返回 500，实际: " + resp.code());
        }
    }
}
