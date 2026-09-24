package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 批量方法的执行线程与池语义：
 * <ul>
 * <li>默认（平台池）→ {@code batch-worker-*} 平台线程；</li>
 * <li>{@code virtualThreads=true}（{@code spring.threads.virtual.enabled=true} + JDK 21+） → {@code batch-virtual-*}
 * 虚拟线程；</li>
 * <li><b>两种模式下池语义一致</b>：{@code consumerSize} 仍是并发上限，池满时由 Disruptor 消费者线程（{@code batch-disruptor-*}）自执行形成背压。</li>
 * </ul>
 */
class DisruptorQueueVirtualThreadsTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private DisruptorQueue queue;

    /** 记录批量方法实际执行线程的 bean。 */
    static class ThreadCapturingService {
        static final AtomicReference<Thread> LAST_THREAD = new AtomicReference<>();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void handle(List<? extends BatchRequest<?>> batch) {
            LAST_THREAD.set(Thread.currentThread());
            for (BatchRequest request : batch) {
                request.setResult("ok");
            }
        }
    }

    /** 慢批量方法：用于制造池饱和，记录所有执行线程名。 */
    static class SaturatedService {
        static final long SLEEP_MS = 300L;
        static final Set<String> THREADS = ConcurrentHashMap.newKeySet();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void handle(List<? extends BatchRequest<?>> batch) {
            THREADS.add(Thread.currentThread().getName());
            try {
                Thread.sleep(SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            for (BatchRequest request : batch) {
                request.setResult("ok");
            }
        }
    }

    @AfterEach
    void shutdown() {
        if (queue != null) {
            queue.shutdown();
        }
        ThreadCapturingService.LAST_THREAD.set(null);
        SaturatedService.THREADS.clear();
    }

    private static BatchRequestMetaData capturingMeta() throws Exception {
        Method m = ThreadCapturingService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, ThreadCapturingService.class, null, "vtq-" + SEQ.incrementAndGet(), 64,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 2, 2);
    }

    private static BatchRequestMetaData saturationMeta(String queueName) throws Exception {
        Method m = SaturatedService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, SaturatedService.class, null, queueName, 64,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 1, // maxBatchSize =
                                                                                              // 1：每个请求单独成批
                1); // consumerSize = 1：池上限 1
    }

    private static void awaitResult(BatchRequest<?> r) throws InterruptedException {
        for (int i = 0; i < 200 && r.getResult() == null; i++) {
            Thread.sleep(20);
        }
    }

    private Thread runOneBatch(boolean virtualThreads) throws Exception {
        queue = new DisruptorQueue("vtq-" + SEQ.incrementAndGet(), capturingMeta(), new ThreadCapturingService(), null,
                virtualThreads);
        queue.enqueue(new BatchRequest<String>() {
        });
        for (int i = 0; i < 200 && ThreadCapturingService.LAST_THREAD.get() == null; i++) {
            Thread.sleep(20);
        }
        Thread t = ThreadCapturingService.LAST_THREAD.get();
        assertNotNull(t, "批量方法应已被执行");
        return t;
    }

    /** 池上限 1 且每请求单独成批：第二个批次无空闲 worker → 由消费者线程自执行（背压语义）。 */
    private void assertSaturatedBatchRunsInlineOnConsumerThread(boolean virtualThreads) throws Exception {
        String queueName = "satq-" + SEQ.incrementAndGet();
        queue = new DisruptorQueue(queueName, saturationMeta(queueName), new SaturatedService(), null, virtualThreads);

        BatchRequest<String> first = new BatchRequest<String>() {
        };
        BatchRequest<String> second = new BatchRequest<String>() {
        };
        queue.enqueue(first);
        queue.enqueue(second);
        awaitResult(first);
        awaitResult(second);

        assertTrue(SaturatedService.THREADS.stream().anyMatch(n -> n.startsWith("batch-disruptor-")),
                "池满时后续批次应由消费者线程自执行（背压），实际执行线程: " + SaturatedService.THREADS);
    }

    @Test
    void platformMode_runsOnBatchWorkerThread() throws Exception {
        Thread t = runOneBatch(false);
        assertTrue(t.getName().startsWith("batch-worker-"), "默认应为平台工作线程，实际: " + t.getName());
    }

    @Test
    void platformMode_whenPoolSaturated_consumerThreadExecutesInline() throws Exception {
        assertSaturatedBatchRunsInlineOnConsumerThread(false);
    }

    @Test
    void virtualThreadMode_runsOnVirtualThread() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "虚拟线程需要 JDK 21+，当前 JDK " + Runtime.version().feature());
        Thread t = runOneBatch(true);
        assertTrue(t.getName().startsWith("batch-virtual-"), "应使用虚拟线程工厂，实际: " + t.getName());
        Method isVirtual = Thread.class.getMethod("isVirtual");
        assertTrue((boolean) isVirtual.invoke(t), "执行线程应为虚拟线程");
    }

    @Test
    void virtualThreadMode_whenPoolSaturated_consumerThreadExecutesInline() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "虚拟线程需要 JDK 21+，当前 JDK " + Runtime.version().feature());
        assertSaturatedBatchRunsInlineOnConsumerThread(true);
        assertTrue(SaturatedService.THREADS.stream().anyMatch(n -> n.startsWith("batch-virtual-")),
                "池未满时仍应在虚拟线程上执行，实际执行线程: " + SaturatedService.THREADS);
    }
}
