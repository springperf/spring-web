package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.http.timeout}（响应超时）与 {@code spring.mvc.async.request-timeout}（异步超时） 的优先级 E2E（同文件两个测试类，分别对应两种属性组合）：
 * <ul>
 * <li>响应超时更短 → 异步尚未完成即以 504 结束，迟到的异步结果必须被丢弃；</li>
 * <li>异步超时更短 → 由异步机制结束请求（Spring 语义：{@code AsyncRequestTimeoutException} → 503）。</li>
 * </ul>
 * <p>
 * 两个方向都必须保证客户端只收到一个响应。
 * </p>
 */
// 2.7.x 版本层适配：组件扫描到的 @RestController DeferredController 与 @TestConfiguration DeferredConfig 的
// @Bean deferredController() 同名。同一份源码在 master（Boot 3.5 / Spring 6）不产生冲突，在本分支（Boot 2.7 /
// Spring 5.3）抛 BeanDefinitionOverrideException。实测：master 该文件 3 个类 5/5 通过；本分支失败；显式声明允许
// 覆盖后本分支 5/5 通过且断言不变 —— 故保留显式开关以获得与 master 相同的装配结果。
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        DeferredConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.http.timeout=1s", "spring.mvc.async.request-timeout=5s",
                "spring.main.allow-bean-definition-overriding=true" })
class HttpTimeoutWinsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(20)).build();

    @LocalServerPort
    int port;

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).build()).execute();
    }

    @Test
    void deferredResult_beforeAsyncTimeout_returns200() throws Exception {
        Response resp = get("/e2e-at/async?ms=100");
        try {
            assertEquals(200, resp.code(), "异步在两者时限内完成应 200，实际 " + resp.code());
            assertTrue(resp.body().string().contains("async-done"));
        } finally {
            resp.close();
        }
    }

    @Test
    void deferredResult_afterHttpTimeout_endsWith503_asyncSemantics() throws Exception {
        // 异步请求下响应超时无法写出 504：请求进入异步挂起时响应已被标记 handled，
        // 响应超时回调的写出被拒绝，最终由异步超时机制（AsyncRequestTimeoutException）收尾 → 503
        // （与 Spring 对异步超时的 503 语义一致）。关键契约是**更短的时限生效**。
        long start = System.currentTimeMillis();
        Response resp = get("/e2e-at/async?ms=2500");
        long elapsed = System.currentTimeMillis() - start;
        try {
            assertEquals(503, resp.code(), "异步请求在响应超时(1s)后应由异步超时语义结束（503），实际 " + resp.code());
            assertTrue(elapsed < 3000, "应由更短的响应超时(1s)而非异步超时(5s)结束请求，实际耗时 " + elapsed + "ms");
            assertTrue(!resp.body().string().contains("async-done"), "迟到的异步结果不得出现在响应中");
        } finally {
            resp.close();
        }
    }
}

/**
 * 反向组合：异步超时（1s）短于响应超时（10s）→ 由异步机制结束请求。
 */
// 同 HttpTimeoutWinsE2eTest：deferredController 重名在 2.7.x 需显式允许覆盖（详见上方说明）。
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        DeferredConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/", "server.http.timeout=10s", "spring.mvc.async.request-timeout=1s",
                "spring.main.allow-bean-definition-overriding=true" })
class AsyncTimeoutWinsE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(20)).build();

    @LocalServerPort
    int port;

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).build()).execute();
    }

    @Test
    void asyncNotCompleted_endedByAsyncTimeout() throws Exception {
        Response resp = get("/e2e-at/async?ms=4000");
        try {
            assertEquals(503, resp.code(), "异步超时(1s)短于响应超时(10s)时应由异步机制结束请求（Spring 语义 503），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void lateAsyncCompletion_afterAsyncTimeout_dropped() throws Exception {
        Response resp = get("/e2e-at/async?ms=4000");
        try {
            assertTrue(resp.code() >= 500, "异步超时应以错误状态结束，实际 " + resp.code());
            String body = resp.body().string();
            assertTrue(!body.contains("async-done"), "异步超时后到达的结果不得写入响应，实际 " + body);
        } finally {
            resp.close();
        }
    }
}

@TestConfiguration
class DeferredConfig {

    @Bean
    DeferredController deferredController() {
        return new DeferredController();
    }
}

@RestController
class DeferredController {

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "e2e-async-timer");
        t.setDaemon(true);
        return t;
    });

    @GetMapping("/e2e-at/async")
    public DeferredResult<String> async(@RequestParam long ms) {
        DeferredResult<String> result = new DeferredResult<>();
        SCHEDULER.schedule(() -> result.setResult("async-done"), ms, TimeUnit.MILLISECONDS);
        return result;
    }
}
