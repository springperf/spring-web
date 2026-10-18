package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchOverflowException;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.metrics.BatchMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 真实构造 DisruptorQueue：验证 enqueue（BLOCK/THROW/DROP）、停机后直连处理、 剩余容量/缓冲大小与优雅停机，覆盖构造函数与 shutdown 全流程。
 */
class DisruptorQueueCoverageTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private DisruptorQueue queue;

    @AfterEach
    void shutdownQueue() {
        if (queue != null) {
            queue.shutdown();
        }
    }

    static class FastService {
        static final AtomicInteger INVOKED = new AtomicInteger();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void handle(List<? extends BatchRequest<?>> batch) {
            INVOKED.incrementAndGet();
            for (BatchRequest request : batch) {
                request.setResult("ok");
            }
        }
    }

    private static BatchRequestMetaData meta(BatchMapping.Backpressure backpressure) throws Exception {
        Method m = FastService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, FastService.class, null, "cq-" + SEQ.incrementAndGet(), 64,
                BatchMapping.WaitStrategy.BLOCKING, backpressure, null, 2, 2);
    }

    private BatchRequest<String> request() {
        return new BatchRequest<String>() {
        };
    }

    private static void awaitResult(BatchRequest<?> r) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            if (r.getResult() != null) {
                return;
            }
            Thread.sleep(20);
        }
    }

    /* ==================== 构造器 ==================== */

    @Test
    void constructor_singleArgUsesNoOpMetrics() throws Exception {
        queue = new DisruptorQueue("single-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService());
        assertNotNull(queue.queueName());
        assertEquals(64, queue.bufferSize(), "ring buffer 应归一化到至少 64");
        assertTrue(queue.remainingCapacity() > 0);
    }

    @Test
    void constructor_withNullMetrics_usesNoOpMetrics() throws Exception {
        queue = new DisruptorQueue("null-metrics-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService(), null);
        assertTrue(queue.bufferSize() > 0);
    }

    @Test
    void constructor_nonPowerOfTwoSize_logsNormalization() throws Exception {
        // 回归：告警条件曾判断「归一化后的 size」，而该值恒在 [64, 262144]，导致告警永不触发、
        // 用户的异常配置被静默接受。现在必须能看见 "normalized to 128"。
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger(DisruptorQueue.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Method odd = FastService.class.getDeclaredMethod("handle", List.class);
            BatchRequestMetaData oddMeta = new BatchRequestMetaData(odd, FastService.class, null,
                    "odd-" + SEQ.incrementAndGet(), 100, BatchMapping.WaitStrategy.BLOCKING,
                    BatchMapping.Backpressure.BLOCK, null, 2, 2);

            queue = new DisruptorQueue(oddMeta.queueName(), oddMeta, new FastService());

            assertEquals(128, queue.bufferSize(), "100 应归一化到 128");
            assertTrue(
                    appender.list.stream()
                            .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                                    && e.getFormattedMessage().contains("normalized to 128")),
                    "非 2 幂尺寸应告警归一化结果，实际日志: " + appender.list);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void constructor_virtualThreadsRequestedButUnavailable_fallsBackToPlatform() throws Exception {
        // JDK < 21 上 VirtualThreadSupport.isAvailable() 为 false：应回落到平台池而不是启动失败
        org.junit.jupiter.api.Assumptions.assumeFalse(io.springperf.web.core.pool.VirtualThreadSupport.isAvailable(),
                "仅 JDK < 21 环境验证回落路径");
        queue = new DisruptorQueue("vt-fallback-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService(), null, true);
        assertNotNull(queue.queueName(), "虚拟线程不可用时应回落平台池并正常启动");
    }

    /* ==================== enqueue BLOCK ==================== */

    @Test
    void enqueue_block_mode_processedByConsumer() throws Exception {
        FastService.INVOKED.set(0);
        queue = new DisruptorQueue("block-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService(), mock(BatchMetrics.class));

        BatchRequest<String> r1 = request();
        BatchRequest<String> r2 = request();
        queue.enqueue(r1);
        queue.enqueue(r2);

        awaitResult(r1);
        awaitResult(r2);
        assertNotNull(r1.getResult(), "BLOCK 模式入队应被消费者处理");
        assertNotNull(r2.getResult());
        assertTrue(FastService.INVOKED.get() > 0);
    }

    /* ==================== 停机后直连处理 ==================== */

    @Test
    void enqueue_afterShutdown_processesDirectly() throws Exception {
        FastService.INVOKED.set(0);
        queue = new DisruptorQueue("halted-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService());
        queue.shutdown();
        queue = null;

        // shutdown 后队列进入 halted：直接调用 batch 方法处理（不走 Disruptor）
        DisruptorQueue halted = new DisruptorQueue("halted2-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.BLOCK), new FastService());
        halted.shutdown();
        BatchRequest<String> r = request();
        halted.enqueue(r);
        halted.shutdown(); // 幂等：halted 已置位，第二次调用直接 return

        assertNotNull(r.getResult(), "停机后请求应被 processDirectly 处理");
        assertTrue(FastService.INVOKED.get() > 0);
    }

    /* ==================== 查询方法 ==================== */

    @Test
    void queueName_remainingCapacity_bufferSize_reflectState() throws Exception {
        queue = new DisruptorQueue("query-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService());
        assertTrue(queue.queueName().startsWith("query-"));
        assertEquals(64, queue.bufferSize());
        assertTrue(queue.remainingCapacity() >= 0);
    }

    /* ==================== enqueue DROP / THROW ==================== */

    @Test
    void enqueue_drop_mode_processedWhenCapacityAvailable() throws Exception {
        FastService.INVOKED.set(0);
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue("drop-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.DROP),
                new FastService(), metrics);

        BatchRequest<String> r = request();
        queue.enqueue(r);
        awaitResult(r);

        assertNotNull(r.getResult(), "DROP 模式容量充足时请求应正常处理");
        verify(metrics).recordEnqueue(anyString(), eq(true));
        verify(metrics, never()).recordDrop(anyString());
    }

    @Test
    void enqueue_throw_mode_processedWhenCapacityAvailable() throws Exception {
        FastService.INVOKED.set(0);
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue("throw-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.THROW),
                new FastService(), metrics);

        BatchRequest<String> r = request();
        assertDoesNotThrow(() -> queue.enqueue(r));
        awaitResult(r);

        assertNotNull(r.getResult(), "THROW 模式容量充足时请求应正常处理");
        verify(metrics).recordEnqueue(anyString(), eq(true));
    }

    /* ==================== RingBuffer 打满：DROP / THROW 的失败分支 ==================== */

    /**
     * 用一个阻塞在 {@code block} 上的批量方法占住消费链路：bizExecutor 满员后由 {@code CallerRunsPolicy} 让 Disruptor 消费者线程自行执行，消费者随之停止拉取，
     * RingBuffer 被逐步填满——这是触发 {@code tryPublishEvent} 失败的最直接手段。
     */
    static class BlockingService {
        static volatile boolean block = false;

        public void handle(List<? extends BatchRequest<?>> batch) throws Exception {
            while (block) {
                Thread.sleep(10);
            }
        }
    }

    private static BatchRequestMetaData blockingMeta(BatchMapping.Backpressure backpressure) throws Exception {
        Method m = BlockingService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, BlockingService.class, null, "cb-" + SEQ.incrementAndGet(), 64,
                BatchMapping.WaitStrategy.BLOCKING, backpressure, null, 2, 2);
    }

    /**
     * 填满 RingBuffer：阻塞消费链路后持续入队，直到剩余容量降为 0。 用 {@code remainingCapacity()} 判定而非固定次数，避免依赖 "恰好多少个请求填满" 的实现细节。
     */
    private void fillRingBuffer(DisruptorQueue q) {
        int guard = 0;
        while (q.remainingCapacity() > 0 && guard++ < 2000) {
            q.enqueue(request());
        }
        assertEquals(0, q.remainingCapacity(), "RingBuffer 应已被填满");
    }

    @Test
    void enqueue_drop_mode_fullBuffer_setsOverflowError() throws Exception {
        BlockingService.block = true;
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue("drop-full-" + SEQ.incrementAndGet(), blockingMeta(BatchMapping.Backpressure.DROP),
                new BlockingService(), metrics);
        try {
            fillRingBuffer(queue);

            BatchRequest<String> overflow = request();
            queue.enqueue(overflow);

            assertTrue(overflow.isCompleted(), "DROP 失败应立即以异步方式完成请求");
            // setErrorResult 写入的即 result 本身：这里断言它携带的是映射 429 的溢出异常
            Object errorResult = overflow.getResult();
            assertTrue(errorResult instanceof BatchOverflowException,
                    "DROP 失败应写入 BatchOverflowException（映射 429），实际: " + errorResult);
            verify(metrics).recordDrop(anyString());
            verify(metrics, never()).recordOverflow(anyString());
        } finally {
            BlockingService.block = false;
        }
    }

    @Test
    void enqueue_drop_mode_fullBuffer_alreadyCompleted_logsAndSkips() throws Exception {
        BlockingService.block = true;
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue("drop-done-" + SEQ.incrementAndGet(), blockingMeta(BatchMapping.Backpressure.DROP),
                new BlockingService(), metrics);
        try {
            fillRingBuffer(queue);

            // 模拟"已因超时完成"的请求：此时客户端已收到响应，无法再收到溢出信号
            BatchRequest<String> timedOut = request();
            timedOut.setResult("already-served");
            queue.enqueue(timedOut);

            assertEquals("already-served", timedOut.getResult(), "已完成请求不得被改写为溢出错误");
            verify(metrics, times(1)).recordDrop(anyString());
        } finally {
            BlockingService.block = false;
        }
    }

    @Test
    void enqueue_throw_mode_fullBuffer_throwsOverflowException() throws Exception {
        BlockingService.block = true;
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue("throw-full-" + SEQ.incrementAndGet(), blockingMeta(BatchMapping.Backpressure.THROW),
                new BlockingService(), metrics);
        try {
            fillRingBuffer(queue);

            BatchOverflowException ex = assertThrows(BatchOverflowException.class, () -> queue.enqueue(request()));
            assertTrue(ex.getMessage().contains("throw-full-"), "异常应带上队列名便于定位: " + ex.getMessage());
            verify(metrics).recordOverflow(anyString());
        } finally {
            BlockingService.block = false;
        }
    }

    @Test
    void shutdown_thenShutdown_idempotent() throws Exception {
        queue = new DisruptorQueue("idem-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK),
                new FastService());
        queue.shutdown();
        assertDoesNotThrow(() -> queue.shutdown(), "重复 shutdown 应幂等");
        queue = null;
    }
}
