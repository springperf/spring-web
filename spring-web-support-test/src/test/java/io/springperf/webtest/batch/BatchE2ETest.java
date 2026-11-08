package io.springperf.webtest.batch;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BatchE2ETest extends BaseE2ETest {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    /** 解析响应前缀后的批大小，如 {@code batched:3:msg=...} → 3。 */
    private static Integer parseBatchSize(String body, String prefix) {
        Matcher matcher = Pattern.compile("^" + prefix + ":(\\d+):").matcher(body);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    @Test
    void batchRequests_areProcessedByBatchHandler() throws Exception {
        int requestCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            final String msg = "hello-" + i;
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder().url(url("/api/batch/echo?msg=" + msg)).get().build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        Integer batchSize = parseBatchSize(body, "batched");
                        // 线程名以 batch- 开头即证明走了 @BatchMapping 路径（EventLoop 线程名不是这个前缀）
                        if (resp.code() == 200 && batchSize != null && body.contains("msg=" + msg)
                                && body.contains(":thread=batch-")) {
                            successCount.incrementAndGet();
                            batchSizes.add(batchSize);
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, successCount.get(),
                "All requests should return 200 with their own msg injected. Failures: " + failures);
        // 每个响应都来自批处理方法，且回显的批大小不能超过总请求数
        assertTrue(batchSizes.stream().allMatch(size -> size >= 1 && size <= requestCount),
                "批大小应在 [1, 请求数] 内，实际: " + batchSizes);
        // 说明：该端点使用默认 2ms 攒批窗口，合并次数仍取决于请求到达时序，不能强求；
        // 窗口 + maxBatchSize 的确定性合并由 maxBatchSize_limitsBatchSizeInResponses 验证
    }

    @Test
    void singleRequest_alsoProcessedByBatchHandler() throws Exception {
        Request req = new Request.Builder().url(url("/api/batch/echo?msg=hello")).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals(1, parseBatchSize(body, "batched"), "单请求应单独成批: " + body);
            assertTrue(body.contains("msg=hello"), "Response should contain injected msg=hello: " + body);
        }
    }

    /** 未开启虚拟线程（默认）：批量方法应在平台工作线程上执行。 */
    @Test
    void batchHandler_runsOnPlatformWorkerThreadByDefault() throws Exception {
        Request req = new Request.Builder().url(url("/api/batch/thread?msg=hello")).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.startsWith("thread=batch-worker-"), "默认应在平台工作线程执行: " + body);
            assertTrue(body.endsWith(":virtual=false"), "默认不应是虚拟线程: " + body);
        }
    }

    @Test
    void postWithMultiParams_injectsBodyQueryAndPathVar() throws Exception {
        String json = "{\"name\":\"testUser\",\"age\":25}";
        Request req = new Request.Builder().url(url("/api/batch/echo/testPath?msg=helloMulti"))
                .post(RequestBody.create(JSON, json)).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals(1, parseBatchSize(body, "batched"), "单请求应单独成批: " + body);
            assertTrue(body.contains("msg=helloMulti"), "Missing msg injection: " + body);
            assertTrue(body.contains("name=testUser"), "Missing body.name injection: " + body);
            assertTrue(body.contains("age=25"), "Missing body.age injection: " + body);
            assertTrue(body.contains("pathVal=testPath"), "Missing pathVal injection: " + body);
        }
    }

    @Test
    void maxBatchSize_limitsBatchSizeInResponses() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<Integer> batchSizes = new CopyOnWriteArrayList<>();
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            final String msg = "test-" + i;
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder().url(url("/api/batch/echo-batchsize?msg=" + msg)).get()
                            .build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        Integer batchSize = parseBatchSize(body, "batched");
                        if (resp.code() == 200 && batchSize != null && batchSize <= 2
                                && body.contains("msg=" + msg) && body.contains(":thread=batch-")) {
                            successCount.incrementAndGet();
                            batchSizes.add(batchSize);
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, successCount.get(),
                "All maxBatchSize requests should succeed with batch size <= 2. Failures: " + failures);
        // 一次「2 个请求成批」必然让该批的两个成员都回显 size=2；只 anyMatch 会放过「仅一个响应谎报 2」。
        assertTrue(batchSizes.stream().allMatch(size -> size >= 1 && size <= 2),
                "maxBatchSize=2 下批大小不得超过 2，实际: " + batchSizes);
        assertTrue(batchSizes.stream().filter(size -> size == 2).count() >= 2,
                "50ms 窗口下 5 个请求应至少有一次 2 个请求成批（两个成员均须回显 2），实际批大小: " + batchSizes);
    }

    @Test
    void exceptionInBatchHandler_propagatesToAllRequests() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder().url(url("/api/batch/echo-error?msg=test")).get().build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        // 本应用 GlobalExceptionHandler 的 @ExceptionHandler(Throwable.class) 把批量方法抛出的
                        // RuntimeException 收敛为 200 + ApiResult.failed(INTERNAL_SERVER_ERROR)（code=500）。
                        // 必须同时锁定状态码与错误码字段：裸 500、2xx 里带任意错误字样都不算「异常已传播」。
                        boolean isError = resp.code() == 200 && body.contains("\"code\":500");
                        if (isError) {
                            errorCount.incrementAndGet();
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, errorCount.get(),
                "All requests should receive error response. Failures: " + failures);
    }

    /**
     * consumerSize=2 的语义：批量方法在 bizExecutor 上执行；池满时由 Disruptor 消费者线程 自执行（CallerRunsPolicy 背压）。两种线程都属于 batch
     * 线程模型，不能要求“全部在 worker 线程”。
     */
    @Test
    void maxConsumers_processesAllRequestsInBatchThreadModel() throws Exception {
        int requestCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < requestCount; i++) {
            final String msg = "test-" + i;
            executor.submit(() -> {
                try {
                    Request req = new Request.Builder().url(url("/api/batch/echo-consumers?msg=" + msg)).get()
                            .build();
                    try (Response resp = CLIENT.newCall(req).execute()) {
                        String body = resp.body() != null ? resp.body().string() : "";
                        Integer batchSize = parseBatchSize(body, "consumed");
                        // 批量方法必须运行在 batch 线程模型（worker 池或消费者线程背压），而不是 EventLoop
                        if (resp.code() == 200 && batchSize != null && body.contains("msg=" + msg)
                                && body.contains("thread=batch-")) {
                            successCount.incrementAndGet();
                        } else {
                            failures.add(resp.code() + ":" + body);
                        }
                    }
                } catch (Exception e) {
                    failures.add("EXCEPTION:" + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(15, TimeUnit.SECONDS), "Requests did not complete within timeout");
        executor.shutdown();

        assertEquals(requestCount, successCount.get(),
                "All maxConsumers requests should run in the batch thread model. Failures: " + failures);
    }
}
