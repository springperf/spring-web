package io.springperf.webtest.configalign;

import io.springperf.web.core.async.PerfAsyncWebRequest;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步 / SSE 生命周期 E2E（引用计数不变式验证）。
 * <p>
 * 判据有两条，缺一不可：
 * </p>
 * <ol>
 * <li><b>响应语义正确</b>：客户端拿到预期内容；</li>
 * <li><b>异步持有者引用归零</b>：{@link PerfAsyncWebRequest#activeRequestRefs()} 回到场景开始前的基线 —— 这是「入站 buf 引用被归还」的直接证据，不依赖 GC
 * 时机。</li>
 * </ol>
 * <p>
 * 覆盖的终结点路径：DeferredResult 正常完成、Callable 正常完成、SSE 正常结束 （onAllDataWritten → completeSuccessCallback）、<b>SSE 空闲流被客户端中断</b>
 * （无 LastHttpContent、无 chunk 写失败 → 只能靠 channelInactive 让异步持有者退场， 即 releaseOnConnectionClose 专治的场景）。
 * </p>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        AsyncSseLifecycleE2eTest.AsyncLifecycleConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
                "server.servlet.context-path=/" })
class AsyncSseLifecycleE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String base() {
        return "http://localhost:" + port;
    }

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new okhttp3.Request.Builder().url(base() + path).build()).execute();
    }

    /** 异步持有者引用必须回到基线（轮询，容忍写终结回调的异步投递延迟）。 */
    private void assertRefsBackTo(int baseline) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (PerfAsyncWebRequest.activeRequestRefs() != baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(baseline, PerfAsyncWebRequest.activeRequestRefs(),
                "场景结束后异步持有者引用应归零（当前值偏离基线 " + baseline + " 说明有未终结的异步生命周期）");
    }

    // ==================== 场景 ====================

    @Test
    void deferredResult_completes_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        try (Response resp = get("/e2e-async/dr")) {
            assertEquals(200, resp.code());
            assertEquals("dr-ok", resp.body().string());
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void callable_completes_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        try (Response resp = get("/e2e-async/callable")) {
            assertEquals(200, resp.code());
            assertEquals("callable-ok", resp.body().string());
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_normalClose_deliversAllChunks_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        try (Response resp = get("/e2e-async/sse")) {
            assertEquals(200, resp.code());
            // 读到 EOF（服务端写完 LastHttpContent 后关闭）—— 证明终止块存在、流正常收尾
            String body = resp.body().string();
            assertTrue(body.contains("chunk-1"), "应收到首块，实际:\n" + body);
            assertTrue(body.contains("chunk-3"), "应收到末块，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_idleClientAbort_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        Response resp = get("/e2e-async/sse-idle");
        try {
            // 读到首块后立刻断开：此时服务端既不写 LastHttpContent，也没有 chunk 写失败，
            // 只有连接关闭事件 —— 若异步持有者不在 channelInactive 退场，引用将永不归还
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应至少收到一块数据");
        } finally {
            resp.close();
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_halfWrittenClientAbort_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        Response resp = get("/e2e-async/sse-slow");
        try {
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应收到第一块");
        } finally {
            resp.close(); // 写到一半断开连接
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_businessErrorTermination_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        try (Response resp = get("/e2e-async/sse-error")) {
            assertEquals(200, resp.code());
            try {
                resp.body().string();
            } catch (java.io.IOException expected) {
                // 设计要求如此：异常终止不写 LastHttpContent、直接关闭连接，让客户端感知
                // 「异常截断」——chunked 流读到 EOF 正是该信号（见 onAllDataFailed 注释）。
                // 此处只要求：请求不悬挂、且不出现双重终止块（编码器异常）。
            }
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void deferredResult_timeoutThenLateResult_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        try (Response resp = get("/e2e-async/dr-late")) {
            int code = resp.code();
            resp.body().string();
            // 超时响应状态码由框架实现决定；此处只要求「请求不悬挂」（结果迟到不得二次写）
            assertTrue(code >= 200 && code < 600, "应得到确定的状态码，实际 " + code);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void bigBody_asyncReadAfterDispatch_isSafe_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        int size = 200_000; // 远超内存阈值 → 走 duplicate 共享视图路径（改造前的风险点）
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i % 251);
        }
        long expectedSum = 0;
        for (byte b : payload) {
            expectedSum += b;
        }

        try (Response resp = CLIENT.newCall(new okhttp3.Request.Builder().url(base() + "/e2e-async/big-body")
                .post(okhttp3.RequestBody.create(payload, okhttp3.MediaType.parse("application/octet-stream"))).build())
                .execute()) {
            assertEquals(200, resp.code(), "异步阶段读 body 不应失败");
            assertEquals("len=" + size + ",sum=" + expectedSum, resp.body().string(),
                    "异步阶段读到的 body 必须完整且内容正确（不得抛异常、不得读到被复用内存）");
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void pipelining_asyncRequestsServedSerially_refsReturnToBaseline() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(10000);
            String one = "GET /e2e-async/dr HTTP/1.1\r\nHost: localhost\r\n\r\n";
            // 同连接连续两个请求（不等第一个响应）→ 框架应串行处理并保序
            socket.getOutputStream().write((one + one).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();

            StringBuilder received = new StringBuilder();
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = socket.getInputStream().read(buf)) != -1) {
                    received.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                    if (occurrences(received.toString(), "dr-ok") >= 2) {
                        break; // 两个响应都到齐
                    }
                }
            } catch (java.net.SocketTimeoutException ignored) {
                // 只到一个即超时 → 由下方断言报出（消息里带实际收到的内容）
            }
            String all = received.toString();
            assertEquals(2, occurrences(all, "dr-ok"), "同连接两个异步请求都应被处理（队列不得丢请求/挂死），实际收到:\n" + all);
        }
        assertRefsBackTo(baseline);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    @Test
    void stress_mixedAsyncSseScenarios_noRefLeftover_noDefectSignals() throws Exception {
        int baseline = PerfAsyncWebRequest.activeRequestRefs();
        int threads = 6;
        int iterationsPerThread = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.atomic.AtomicInteger clientFailures = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                java.util.Random random = new java.util.Random(seed);
                for (int i = 0; i < iterationsPerThread; i++) {
                    try {
                        int scenario = random.nextInt(8);
                        if (scenario == 7) {
                            postBigBody(64_000);
                        } else {
                            hammer(scenario);
                        }
                    } catch (Throwable e) {
                        clientFailures.incrementAndGet();
                    }
                }
            }));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(120, java.util.concurrent.TimeUnit.SECONDS), "并发压测应在 120s 内完成");
        assertEquals(0, clientFailures.get(), "压测期间不应出现客户端异常");
        // 所有异步持有者必须全部退场；服务端缺陷信号由 @AfterEach 的日志判据把关
        assertRefsBackTo(baseline);
    }

    /** 单次场景请求（0..6）：正常异步 / 正常流式 / 断连流式 / 超时迟到 / 业务异常终止。 */
    private void hammer(int scenario) throws Exception {
        switch (scenario) {
            case 0 -> {
                try (Response r = get("/e2e-async/dr")) {
                    r.body().string();
                }
            }
            case 1 -> {
                try (Response r = get("/e2e-async/callable")) {
                    r.body().string();
                }
            }
            case 2 -> {
                try (Response r = get("/e2e-async/sse")) {
                    r.body().string();
                }
            }
            case 3 -> {
                // 写一半即断：读到首块后 try-with-resources 关闭连接
                try (Response r = get("/e2e-async/sse-slow")) {
                    r.body().source().readUtf8Line();
                }
            }
            case 4 -> {
                // 空闲流即断：发送器既无 LastHttpContent 也无 chunk 写失败
                try (Response r = get("/e2e-async/sse-idle")) {
                    r.body().source().readUtf8Line();
                }
            }
            case 5 -> {
                try (Response r = get("/e2e-async/dr-late")) {
                    r.body().string();
                }
            }
            default -> {
                try (Response r = get("/e2e-async/sse-error")) {
                    try {
                        r.body().string();
                    } catch (java.io.IOException expectedTruncation) {
                        // 设计行为：异常终止以「截断」示人
                    }
                }
            }
        }
    }

    private void postBigBody(int size) throws Exception {
        byte[] payload = new byte[size];
        for (int i = 0; i < size; i++) {
            payload[i] = (byte) (i % 251);
        }
        try (Response resp = CLIENT.newCall(new okhttp3.Request.Builder().url(base() + "/e2e-async/big-body")
                .post(okhttp3.RequestBody.create(payload, okhttp3.MediaType.parse("application/octet-stream"))).build())
                .execute()) {
            assertEquals(200, resp.code());
            resp.body().string();
        }
    }

    // ==================== 服务端日志判据 ====================
    //
    // 背景：SSE 双终止块缺陷表现为「客户端用例全绿、服务端日志报 EncoderException」——
    // 只断言客户端可见行为永远抓不到这类问题。故每个场景结束后断言发送器日志无 WARN/ERROR。

    private ch.qos.logback.classic.Logger streamLogLogger;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> streamLogAppender;

    @org.junit.jupiter.api.BeforeEach
    void attachStreamLogCapture() {
        streamLogLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("io.springperf.web.core.async.stream.AbstractNettyStreamSender");
        streamLogAppender = new ch.qos.logback.core.read.ListAppender<>();
        streamLogAppender.start();
        streamLogLogger.addAppender(streamLogAppender);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoServerSideStreamWarnings() {
        streamLogLogger.detachAppender(streamLogAppender);
        streamLogAppender.stop();
        java.util.List<String> problems = streamLogAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)
                        || isDefectSignal(e.getFormattedMessage()))
                .map(e -> e.getLevel() + ": " + e.getFormattedMessage()).collect(java.util.stream.Collectors.toList());
        assertTrue(problems.isEmpty(), "服务端流式发送器出现缺陷信号（双终止块/编码器异常/终止块写失败），实际:\n" + problems);
    }

    /**
     * 缺陷信号判定：编码器状态机异常、终止块写失败等。
     * <p>
     * 「业务异常终止」的设计内 WARN（{@code [SSE] stream terminated with error}）不算缺陷—— 那是框架对客户端的异常截断信号，属预期行为。
     * </p>
     */
    private static boolean isDefectSignal(String message) {
        return message != null && (message.contains("write ERROR") || message.contains("EncoderException")
                || message.contains("unexpected message type") || message.contains("endStream failed"));
    }

    // ==================== 测试应用 ====================

    @TestConfiguration
    static class AsyncLifecycleConfig {
        @Bean
        AsyncLifecycleController asyncLifecycleController() {
            return new AsyncLifecycleController();
        }
    }

    @RestController
    static class AsyncLifecycleController {

        @GetMapping("/e2e-async/dr")
        public DeferredResult<String> deferred() {
            DeferredResult<String> result = new DeferredResult<>(5000L);
            Thread worker = new Thread(() -> {
                sleepQuietly(20);
                result.setResult("dr-ok");
            }, "e2e-dr-worker");
            worker.setDaemon(true);
            worker.start();
            return result;
        }

        @GetMapping("/e2e-async/callable")
        public Callable<String> callable() {
            return () -> "callable-ok";
        }

        @GetMapping(value = "/e2e-async/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public io.springperf.web.core.async.stream.SseEmitter sse() {
            io.springperf.web.core.async.stream.SseEmitter emitter = new io.springperf.web.core.async.stream.SseEmitter();
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 1; i <= 3; i++) {
                        emitter.send("chunk-" + i);
                        sleepQuietly(30);
                    }
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-sse-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        /** 空闲流：只发一块、永不 complete —— 用于验证客户端中断时异步持有者能从 channelInactive 退场。 */
        @GetMapping(value = "/e2e-async/sse-idle", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public io.springperf.web.core.async.stream.SseEmitter sseIdle() throws java.io.IOException {
            io.springperf.web.core.async.stream.SseEmitter emitter = new io.springperf.web.core.async.stream.SseEmitter();
            emitter.send("chunk-1");
            return emitter;
        }

        /** 写一半：发一块后停顿，便于客户端在写到一半时断开。 */
        @GetMapping(value = "/e2e-async/sse-slow", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public io.springperf.web.core.async.stream.SseEmitter sseSlow() {
            io.springperf.web.core.async.stream.SseEmitter emitter = new io.springperf.web.core.async.stream.SseEmitter();
            Thread worker = new Thread(() -> {
                try {
                    emitter.send("chunk-1");
                    sleepQuietly(400);
                    emitter.send("chunk-2");
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-sse-slow-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        /** 业务异常终止：发一块后 completeWithError → 流以错误收尾（onAllDataFailed 路径）。 */
        @GetMapping(value = "/e2e-async/sse-error", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public io.springperf.web.core.async.stream.SseEmitter sseError() {
            io.springperf.web.core.async.stream.SseEmitter emitter = new io.springperf.web.core.async.stream.SseEmitter();
            Thread worker = new Thread(() -> {
                try {
                    emitter.send("chunk-1");
                    sleepQuietly(50);
                    emitter.completeWithError(new IllegalStateException("boom"));
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-sse-error-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        /**
         * 大 body + 异步阶段读 body：handler 返回后（异步已启动、同步阶段已结束）才在 worker 线程 读取请求体。这是本次「异步持有者 acquire」改造的核心收益 —— 改造前入站 buf
         * 已在同步末尾 释放，大 body 走 duplicate 共享视图，读取会抛异常或读到被复用内存（静默串包）。
         */
        @org.springframework.web.bind.annotation.PostMapping("/e2e-async/big-body")
        public DeferredResult<String> bigBody(jakarta.servlet.http.HttpServletRequest request) {
            DeferredResult<String> result = new DeferredResult<>(5000L);
            Thread worker = new Thread(() -> {
                try {
                    sleepQuietly(50); // 确保同步阶段已结束、异步持有者已 acquire
                    byte[] body = request.getInputStream().readAllBytes();
                    long sum = 0;
                    for (byte b : body) {
                        sum += b;
                    }
                    result.setResult("len=" + body.length + ",sum=" + sum);
                } catch (Throwable t) {
                    result.setErrorResult(t);
                }
            }, "e2e-bigbody-worker");
            worker.setDaemon(true);
            worker.start();
            return result;
        }

        /** 超时后结果迟到：DeferredResult 150ms 超时，worker 600ms 才 setResult。 */
        @GetMapping("/e2e-async/dr-late")
        public DeferredResult<String> deferredLate() {
            DeferredResult<String> result = new DeferredResult<>(150L);
            Thread worker = new Thread(() -> {
                sleepQuietly(600);
                result.setResult("late-result");
            }, "e2e-dr-late-worker");
            worker.setDaemon(true);
            worker.start();
            return result;
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 订阅建立后再投递的 Publisher。
     * <p>
     * 不能用 {@code SubmissionPublisher}：它在「无订阅者时 submit」会直接丢弃数据， 而框架是在 handler 返回后才订阅——先 submit 的块会丢（本测试首版即栽在这里）。
     * 本实现把投递线程放在 {@link #subscribe} 内部启动，投递时机与订阅严格对齐。
     * </p>
     */
    static final class DelayedPublisher implements Flow.Publisher<String> {

        private final int chunks;
        private final boolean complete;

        DelayedPublisher(int chunks, boolean complete) {
            this.chunks = chunks;
            this.complete = complete;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super String> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // 推送式：本测试不模拟需求驱动的背压（框架侧背压由 StreamSender 负责）
                }

                @Override
                public void cancel() {
                }
            });
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 1; i <= chunks; i++) {
                        subscriber.onNext("chunk-" + i + "\n");
                        AsyncLifecycleController.sleepQuietly(30);
                    }
                    if (complete) {
                        subscriber.onComplete();
                    }
                } catch (Throwable t) {
                    subscriber.onError(t);
                }
            }, "e2e-sse-worker");
            worker.setDaemon(true);
            worker.start();
        }
    }
}
