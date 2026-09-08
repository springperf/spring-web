package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
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
 * 真实构造 DisruptorQueue：验证 enqueue（BLOCK/THROW/DROP）、停机后直连处理、
 * 剩余容量/缓冲大小与优雅停机，覆盖构造函数与 shutdown 全流程。
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

        @SuppressWarnings({"unchecked", "rawtypes"})
        public void handle(List<? extends BatchRequest<?>> batch) {
            INVOKED.incrementAndGet();
            for (BatchRequest request : batch) {
                request.setResult("ok");
            }
        }
    }

    private static BatchRequestMetaData meta(BatchMapping.Backpressure backpressure) throws Exception {
        Method m = FastService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(
                m, FastService.class, null, "cq-" + SEQ.incrementAndGet(),
                64, BatchMapping.WaitStrategy.BLOCKING, backpressure, null, 2, 2
        );
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
        queue = new DisruptorQueue("single-" + SEQ.incrementAndGet(), meta(BatchMapping.Backpressure.BLOCK), new FastService());
        assertNotNull(queue.queueName());
        assertEquals(64, queue.bufferSize(), "ring buffer 应归一化到至少 64");
        assertTrue(queue.remainingCapacity() > 0);
    }

    @Test
    void constructor_withNullMetrics_usesNoOpMetrics() throws Exception {
        queue = new DisruptorQueue("null-metrics-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.BLOCK), new FastService(), null);
        assertTrue(queue.bufferSize() > 0);
    }

    /* ==================== enqueue BLOCK ==================== */

    @Test
    void enqueue_block_mode_processedByConsumer() throws Exception {
        FastService.INVOKED.set(0);
        queue = new DisruptorQueue("block-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.BLOCK), new FastService(), mock(BatchMetrics.class));

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
        queue = new DisruptorQueue("halted-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.BLOCK), new FastService());
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
        queue = new DisruptorQueue("query-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.BLOCK), new FastService());
        assertTrue(queue.queueName().startsWith("query-"));
        assertEquals(64, queue.bufferSize());
        assertTrue(queue.remainingCapacity() >= 0);
    }

    /* ==================== enqueue DROP / THROW ==================== */

    @Test
    void enqueue_drop_mode_processedWhenCapacityAvailable() throws Exception {
        FastService.INVOKED.set(0);
        BatchMetrics metrics = mock(BatchMetrics.class);
        queue = new DisruptorQueue("drop-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.DROP), new FastService(), metrics);

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
        queue = new DisruptorQueue("throw-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.THROW), new FastService(), metrics);

        BatchRequest<String> r = request();
        assertDoesNotThrow(() -> queue.enqueue(r));
        awaitResult(r);

        assertNotNull(r.getResult(), "THROW 模式容量充足时请求应正常处理");
        verify(metrics).recordEnqueue(anyString(), eq(true));
    }

    @Test
    void shutdown_thenShutdown_idempotent() throws Exception {
        queue = new DisruptorQueue("idem-" + SEQ.incrementAndGet(),
                meta(BatchMapping.Backpressure.BLOCK), new FastService());
        queue.shutdown();
        assertDoesNotThrow(() -> queue.shutdown(), "重复 shutdown 应幂等");
        queue = null;
    }
}
