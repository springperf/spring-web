package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.metrics.NoOpBatchMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BufferingBatchHandlerTest {

    static class TestService {
        @SuppressWarnings("unused")
        public void handle(List<? extends BatchRequest<?>> batch) {
        }
    }

    @Mock
    private Executor executor;

    private BatchRequestMetaData meta;
    private TestService bean;
    private Method batchMethod;

    @BeforeEach
    void setUp() throws Exception {
        bean = new TestService();
        batchMethod = TestService.class.getDeclaredMethod("handle", List.class);
        meta = new BatchRequestMetaData(batchMethod, TestService.class, null, "test-queue", 1024,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 0, 4);
    }

    @Test
    void onEvent_accumulatesRequests() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);

        // No flush yet — endOfBatch is false and maxBatchSize is 0
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void endOfBatch_triggersFlush() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, true);

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void maxBatchSize_triggersFlush() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void flush_submitsAllAccumulatedRequests() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(1)).execute(captor.capture());

        // Should NOT throw — batch method invocation is set up correctly
        captor.getValue().run();
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void processBatch_invokesBatchMethod() throws Exception {
        TestService spy = spy(bean);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, spy, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        verify(spy).handle(anyList());
    }

    @Test
    void processBatch_setsErrorOnException() {
        // A bean that throws
        Object badBean = new Object();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, badBean, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void processBatch_setsErrorOnAllRequestsWhenOneFails() {
        Object badBean = new Object();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, badBean, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(r1.isCompleted()).isTrue();
        assertThat(r2.isCompleted()).isTrue();
    }

    /**
     * 业务异常经 {@code InvocationTargetException} 抛出走第一个 catch；反射调用本身失败（如方法不可访问）抛的是 {@code IllegalAccessException}，必须由
     * {@code catch (Throwable)} 兜底——否则该请求既不会被 setError 也不会被记录，客户端只能等 30s 超时。
     */
    @Test
    void processBatch_nonReflectiveFailure_setsErrorToo() throws Exception {
        Method inaccessible = InaccessibleService.class.getDeclaredMethod("handle", List.class);
        BatchRequestMetaData inaccessibleMeta = new BatchRequestMetaData(inaccessible, InaccessibleService.class, null,
                "test-queue", 1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 0, 4);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, inaccessibleMeta, new InaccessibleService(),
                0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.isCompleted()).isTrue();
        assertThat(req.getResult()).isInstanceOf(IllegalAccessException.class);
    }

    static class InaccessibleService {
        /** 私有方法：未 setAccessible 时反射 invoke 抛 IllegalAccessException。 */
        @SuppressWarnings("unused")
        private void handle(List<? extends BatchRequest<?>> batch) {
        }
    }

    @Test
    void processBatch_alreadyCompletedRequestsSkippedInErrorHandling() {
        Object badBean = new Object();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, badBean, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        req.setResult("already-done");

        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        // Already completed — setError should be noop, but it's already completed
        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void onEvent_endOfBatch_flushesOnce() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);
        // endOfBatch=true 时单事件也应触发一次 flush（提交到 executor）
        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, true);
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void flushRemaining_emptyBuffer_doesNothing() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);
        // 空缓冲：flushRemaining 不应调度任何任务（提前 return）
        handler.flushRemaining();
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void flushRemaining_nonEmptyBuffer_flushes() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);
        // 优雅停机路径：缓冲非空时 flushRemaining 应把剩余请求提交执行
        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, false);
        handler.flushRemaining();
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    /**
     * 业务池在停机后拒绝任务时，批次不能静默消失——否则批内请求只能等 DeferredResult 超时（默认 30s）。
     */
    @Test
    void submit_executorRejects_failsRequestsExplicitly() {
        Executor rejecting = r -> {
            throw new RejectedExecutionException("pool is shut down");
        };
        BufferingBatchHandler handler = new BufferingBatchHandler(rejecting, meta, bean, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        assertThat(req.isCompleted()).isTrue();
        assertThat(req.getResult()).isInstanceOf(RejectedExecutionException.class);
    }

    /**
     * 批量方法「正常返回但漏调 setResult」必须以显式错误收尾：不兜底的话客户端要悬停到 30s 超时， 且日志里没有任何线索。
     */
    @Test
    void processBatch_handlerReturnsWithoutCompleting_failsRequestInsteadOfHanging() {
        // TestService.handle 是空实现：不给任何请求设置结果
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.isCompleted()).as("漏完成的请求必须被显式失败，而不是挂到超时").isTrue();
        assertThat(req.getResult()).isInstanceOf(IllegalStateException.class);
    }

    /** 每批处理结束后上报一次 RingBuffer 容量（Gauge 数据源）。 */
    @Test
    void processBatch_reportsCapacityOncePerBatch() {
        AtomicInteger reports = new AtomicInteger();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0, NoOpBatchMetrics.INSTANCE,
                reports::incrementAndGet);

        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(reports).hasValue(1);
    }

    /** 指标上报失败不得改变业务批次的结果。 */
    @Test
    void processBatch_capacityReportFails_batchResultUnaffected() throws Exception {
        Method completing = CompletingService.class.getDeclaredMethod("handle", List.class);
        BatchRequestMetaData completingMeta = new BatchRequestMetaData(completing, CompletingService.class, null,
                "test-queue", 1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 0, 4);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, completingMeta, new CompletingService(), 0,
                NoOpBatchMetrics.INSTANCE, () -> {
                    throw new IllegalStateException("meter registry closed");
                });

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.getResult()).isEqualTo("ok");
    }

    static class CompletingService {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void handle(List<? extends BatchRequest<?>> batch) {
            for (BatchRequest request : batch) {
                request.setResult("ok");
            }
        }
    }

    private static BatchEvent newEvent(BatchRequest<?> request) {
        BatchEvent event = new BatchEvent();
        event.request = request;
        return event;
    }
}
