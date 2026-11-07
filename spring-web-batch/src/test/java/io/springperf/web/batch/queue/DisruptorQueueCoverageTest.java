package io.springperf.web.batch.queue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchOverflowException;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.metrics.BatchMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
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
        static final AtomicReference<Thread> LAST_THREAD = new AtomicReference<>();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void handle(List<? extends BatchRequest<?>> batch) {
            INVOKED.incrementAndGet();
            LAST_THREAD.set(Thread.currentThread());
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
        fail("等待请求完成超时: " + r);
    }

    /* ==================== 构造器 ==================== */

    @Test
    void constructor_singleArgUsesNoOpMetrics() throws Exception {
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        queue = new DisruptorQueue(m.queueName(), m, new FastService());

        assertEquals(m.queueName(), queue.queueName());
        assertEquals(64, queue.bufferSize(), "ring buffer 应归一化到至少 64");
        assertEquals(64, queue.remainingCapacity(), "刚启动时应为满容量");
    }

    @Test
    void constructor_withNullMetrics_usesNoOpMetrics() throws Exception {
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        queue = new DisruptorQueue(m.queueName(), m, new FastService(), null);

        assertEquals(64, queue.bufferSize());
        // null 指标必须被替换为 NoOp：真实入队一次不能因空指标抛 NPE
        BatchRequest<String> r = request();
        queue.enqueue(r);
        awaitResult(r);
        assertEquals("ok", r.getResult());
    }

    @Test
    void constructor_nonPowerOfTwoSize_logsNormalization() throws Exception {
        // 回归：告警条件曾判断「归一化后的 size」，而该值恒在 [64, 262144]，导致告警永不触发、
        // 用户的异常配置被静默接受。现在必须能看见 "normalized to 128"。
        Logger logger = (Logger) LoggerFactory.getLogger(DisruptorQueue.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Method odd = FastService.class.getDeclaredMethod("handle", List.class);
            BatchRequestMetaData oddMeta = new BatchRequestMetaData(odd, FastService.class, null,
                    "odd-" + SEQ.incrementAndGet(), 100, BatchMapping.WaitStrategy.BLOCKING,
                    BatchMapping.Backpressure.BLOCK, null, 2, 2);

            queue = new DisruptorQueue(oddMeta.queueName(), oddMeta, new FastService());

            assertEquals(128, queue.bufferSize(), "100 应归一化到 128");
            assertTrue(appender.list.stream()
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
        FastService.INVOKED.set(0);
        FastService.LAST_THREAD.set(null);
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        queue = new DisruptorQueue(m.queueName(), m, new FastService(), null, true);

        BatchRequest<String> r = request();
        queue.enqueue(r);
        awaitResult(r);

        assertEquals("ok", r.getResult(), "回落后仍应正常处理请求");
        assertNotNull(FastService.LAST_THREAD.get());
        assertEquals("batch-worker-" + m.queueName(), FastService.LAST_THREAD.get().getName(),
                "虚拟线程不可用时必须回落到平台工作线程");
    }

    /* ==================== enqueue BLOCK ==================== */

    @Test
    void enqueue_block_mode_processedByConsumer() throws Exception {
        FastService.INVOKED.set(0);
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue(m.queueName(), m, new FastService(), metrics);

        BatchRequest<String> r1 = request();
        BatchRequest<String> r2 = request();
        queue.enqueue(r1);
        queue.enqueue(r2);

        awaitResult(r1);
        awaitResult(r2);
        assertEquals("ok", r1.getResult());
        assertEquals("ok", r2.getResult());
        assertTrue(FastService.INVOKED.get() > 0);
        verify(metrics, times(2)).recordEnqueue(eq(m.queueName()), eq(true));
    }

    /* ==================== 停机后直连处理 ==================== */

    @Test
    void enqueue_afterShutdown_processesDirectly() throws Exception {
        FastService.INVOKED.set(0);
        FastService.LAST_THREAD.set(null);
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        queue = new DisruptorQueue(m.queueName(), m, new FastService());
        queue.shutdown();

        // shutdown 后队列进入 halted：直接调用 batch 方法处理（不走 Disruptor）
        BatchRequest<String> r = request();
        queue.enqueue(r);
        queue.shutdown(); // 幂等：halted 已置位，第二次调用直接 return

        assertEquals("ok", r.getResult(), "停机后请求应被 processDirectly 处理");
        assertEquals(1, FastService.INVOKED.get(), "每个停机后请求只应触发一次批量方法");
        assertSame(Thread.currentThread(), FastService.LAST_THREAD.get(),
                "processDirectly 必须在生产者线程同步执行，而不是另起线程");
    }

    /* ==================== 查询方法 ==================== */

    @Test
    void queueName_remainingCapacity_bufferSize_reflectState() throws Exception {
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        queue = new DisruptorQueue(m.queueName(), m, new FastService());

        assertEquals(m.queueName(), queue.queueName());
        assertEquals(64, queue.bufferSize());
        assertEquals(64, queue.remainingCapacity());
    }

    /* ==================== enqueue DROP / THROW ==================== */

    @Test
    void enqueue_drop_mode_processedWhenCapacityAvailable() throws Exception {
        FastService.INVOKED.set(0);
        BatchMetrics metrics = mock(BatchMetrics.class);
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.DROP);
        queue = new DisruptorQueue(m.queueName(), m, new FastService(), metrics);

        BatchRequest<String> r = request();
        queue.enqueue(r);
        awaitResult(r);

        assertEquals("ok", r.getResult());
        verify(metrics).recordEnqueue(eq(m.queueName()), eq(true));
        verify(metrics, never()).recordDrop(anyString());
    }

    @Test
    void enqueue_throw_mode_processedWhenCapacityAvailable() throws Exception {
        FastService.INVOKED.set(0);
        BatchMetrics metrics = mock(BatchMetrics.class);
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.THROW);
        queue = new DisruptorQueue(m.queueName(), m, new FastService(), metrics);

        BatchRequest<String> r = request();
        assertDoesNotThrow(() -> queue.enqueue(r));
        awaitResult(r);

        assertEquals("ok", r.getResult());
        verify(metrics).recordEnqueue(eq(m.queueName()), eq(true));
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
        BatchRequestMetaData m = blockingMeta(BatchMapping.Backpressure.DROP);
        queue = new DisruptorQueue(m.queueName(), m, new BlockingService(), metrics);
        try {
            fillRingBuffer(queue);

            BatchRequest<String> overflow = request();
            queue.enqueue(overflow);

            assertTrue(overflow.isCompleted(), "DROP 失败应立即以异步方式完成请求");
            Object errorResult = overflow.getResult();
            assertTrue(errorResult instanceof BatchOverflowException,
                    "DROP 失败应写入 BatchOverflowException（映射 429），实际: " + errorResult);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS,
                    ((BatchOverflowException) errorResult).getStatusCode());
            verify(metrics).recordDrop(eq(m.queueName()));
            verify(metrics, never()).recordOverflow(anyString());
        } finally {
            BlockingService.block = false;
        }
    }

    @Test
    void enqueue_drop_mode_fullBuffer_alreadyCompleted_logsAndSkips() throws Exception {
        BlockingService.block = true;
        BatchMetrics metrics = mock(BatchMetrics.class);
        BatchRequestMetaData m = blockingMeta(BatchMapping.Backpressure.DROP);
        queue = new DisruptorQueue(m.queueName(), m, new BlockingService(), metrics);
        try {
            fillRingBuffer(queue);

            // 模拟"已因超时完成"的请求：此时客户端已收到响应，无法再收到溢出信号
            BatchRequest<String> timedOut = request();
            timedOut.setResult("already-served");
            queue.enqueue(timedOut);

            assertEquals("already-served", timedOut.getResult(), "已完成请求不得被改写为溢出错误");
            verify(metrics, times(1)).recordDrop(eq(m.queueName()));
        } finally {
            BlockingService.block = false;
        }
    }

    @Test
    void enqueue_throw_mode_fullBuffer_throwsOverflowException() throws Exception {
        BlockingService.block = true;
        BatchMetrics metrics = mock(BatchMetrics.class);
        BatchRequestMetaData m = blockingMeta(BatchMapping.Backpressure.THROW);
        queue = new DisruptorQueue(m.queueName(), m, new BlockingService(), metrics);
        try {
            fillRingBuffer(queue);

            BatchOverflowException ex = assertThrows(BatchOverflowException.class, () -> queue.enqueue(request()));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, ex.getStatusCode());
            assertTrue(ex.getMessage().contains(m.queueName()), "异常应带上队列名便于定位: " + ex.getMessage());
            verify(metrics).recordOverflow(eq(m.queueName()));
        } finally {
            BlockingService.block = false;
        }
    }

    /* ==================== 容量指标 ==================== */

    /**
     * 回归：{@code BatchMetrics#reportQueueCapacity} 曾经没有任何生产调用方，对外文档承诺的
     * {@code batch.queue.remaining} / {@code batch.queue.capacity} Gauge 因此永远不会注册。
     */
    @Test
    void processBatch_reportsQueueCapacity() throws Exception {
        BatchMetrics metrics = mock(BatchMetrics.class);
        BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
        queue = new DisruptorQueue(m.queueName(), m, new FastService(), metrics);

        // 启动期预热一次：Gauge 首次注册不能落在请求路径上
        verify(metrics).reportQueueCapacity(m.queueName(), 64, 64);

        BatchRequest<String> r = request();
        queue.enqueue(r);
        awaitResult(r);

        // 批次处理结束后再次上报：参数必须是本队列名、满容量，且最终剩余容量回到 64
        ArgumentCaptor<Integer> remaining = ArgumentCaptor.forClass(Integer.class);
        verify(metrics, timeout(2000).times(2)).reportQueueCapacity(eq(m.queueName()), remaining.capture(), eq(64));
        assertEquals(64, remaining.getAllValues().get(1), "批次处理完成后剩余容量应回到满容量");
    }

    @Test
    void shutdown_thenShutdown_idempotent() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(DisruptorQueue.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BatchRequestMetaData m = meta(BatchMapping.Backpressure.BLOCK);
            queue = new DisruptorQueue(m.queueName(), m, new FastService());

            queue.shutdown();
            queue.shutdown();

            long gracefulLogs = appender.list.stream()
                    .filter(e -> e.getFormattedMessage().contains("shut down gracefully"))
                    .filter(e -> e.getFormattedMessage().contains(m.queueName()))
                    .count();
            assertEquals(1, gracefulLogs, "第二次 shutdown 必须直接返回，不得重复执行停机流程");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
