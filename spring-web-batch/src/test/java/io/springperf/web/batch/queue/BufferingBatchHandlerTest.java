package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.metrics.BatchMetrics;
import io.springperf.web.batch.metrics.NoOpBatchMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BufferingBatchHandlerTest {

    static class TestService {
        @SuppressWarnings("unused")
        public void handle(List<? extends BatchRequest<?>> batch) {
        }
    }

    static class ThrowingService {
        @SuppressWarnings("unused")
        public void handle(List<? extends BatchRequest<?>> batch) {
            throw new IllegalStateException("boom");
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
        meta = new BatchRequestMetaData(
                batchMethod, TestService.class, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                null, 0, 4
        );
    }

    @Test
    void onEvent_accumulatesRequests() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchEvent event = newEvent(r1);
        handler.onEvent(event, 0L, false);

        // No flush yet — endOfBatch is false and maxBatchSize is 0
        verify(executor, never()).execute(any(Runnable.class));
        // 事件槽位必须立即释放，否则 RingBuffer 会钉住已完成的 BatchRequest
        assertThat(event.request()).isNull();
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
        TestService spy = spy(bean);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, spy, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(1)).execute(captor.capture());
        captor.getValue().run();

        // 一批必须同时包含两个请求，且顺序与到达顺序一致
        ArgumentCaptor<List<BatchRequest<?>>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(spy).handle(batchCaptor.capture());
        assertThat(batchCaptor.getValue()).containsExactly(r1, r2);
        // 空实现漏调 setResult 时，两个请求都必须被显式失败，而不是挂到超时
        assertThat(r1.getResult()).isInstanceOf(IllegalStateException.class);
        assertThat(r2.getResult()).isSameAs(r1.getResult());
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

        ArgumentCaptor<List<BatchRequest<?>>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(spy).handle(batchCaptor.capture());
        assertThat(batchCaptor.getValue()).containsExactly(req);
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

        // 反射调用失败必须把真实异常写入请求；只断言 isCompleted() 无法区分
        // "异常被正确写入" 与 "异常被吞后由 failUncompleted 兜底"
        assertThat(req.getResult()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processBatch_businessException_unwrapsInvocationTargetException() throws Exception {
        Method throwing = ThrowingService.class.getDeclaredMethod("handle", List.class);
        BatchRequestMetaData throwingMeta = new BatchRequestMetaData(throwing, ThrowingService.class, null,
                "test-queue", 1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 0, 4);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, throwingMeta, new ThrowingService(), 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        // InvocationTargetException 必须解包成业务异常，客户端不应看到反射包装体
        assertThat(req.getResult()).isInstanceOf(IllegalStateException.class);
        assertThat(((Throwable) req.getResult()).getMessage()).isEqualTo("boom");
        assertThat(req.getResult()).isNotInstanceOf(InvocationTargetException.class);
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

        assertThat(r1.getResult()).isInstanceOf(IllegalArgumentException.class);
        // 同一批的所有请求必须拿到同一个错误对象
        assertThat(r2.getResult()).isSameAs(r1.getResult());
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

        // 已完成请求的原结果不得被错误处理改写
        assertThat(req.getResult()).isEqualTo("already-done");
    }

    /**
     * 竞态：容器超时先到（客户端已收到 503），批次异常处理随后到达。
     * <p>
     * {@code handleBatchError → failRequests → req.setError} 必须被 {@link BatchRequest} 的 CAS 守卫拒绝，
     * 否则会在请求已完成之后改写结果，造成日志/指标与实际响应不一致。
     * </p>
     */
    @Test
    void processBatch_timedOutRequestIsNotOverwrittenByBatchError() {
        AsyncRequestTimeoutException timeout = new AsyncRequestTimeoutException();
        BatchRequest<String> req = new BatchRequest<String>() {
        };
        assertThat(req.setErrorResult(timeout)).isTrue();

        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, new Object(), 0);
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.getResult()).as("批次异常不得覆盖已超时的结果").isSameAs(timeout);
    }

    /**
     * 竞态：容器超时先到，批次方法正常返回但漏调 setResult，走 {@code failUncompleted} 兜底。
     * 兜底同样不得覆盖超时错误。
     */
    @Test
    void processBatch_timedOutRequestIsNotOverwrittenByFallback() {
        AsyncRequestTimeoutException timeout = new AsyncRequestTimeoutException();
        BatchRequest<String> req = new BatchRequest<String>() {
        };
        assertThat(req.setErrorResult(timeout)).isTrue();

        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.getResult()).as("漏完成兜底不得覆盖已超时的结果").isSameAs(timeout);
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

        assertThat(req.getResult()).isInstanceOf(RejectedExecutionException.class);
        assertThat(((Throwable) req.getResult()).getMessage()).isEqualTo("pool is shut down");
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
        assertThat(((Throwable) req.getResult()).getMessage())
                .contains("returned without completing request")
                .contains("test-queue");
    }

    /** 每批处理结束后上报一次 RingBuffer 容量（Gauge 数据源）。 */
    @Test
    void processBatch_reportsCapacityOncePerBatch() {
        AtomicInteger reports = new AtomicInteger();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 2, NoOpBatchMetrics.INSTANCE,
                reports::incrementAndGet);

        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, false);
        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        // 两个请求组成一批：上报次数必须是 1（每批一次），不能是 2（每请求一次）
        assertThat(reports).hasValue(1);
    }

    /** 成功批次必须上报队列名与真实批大小。 */
    @Test
    void processBatch_reportsMetricsWithQueueNameAndBatchSize() {
        BatchMetrics metrics = mock(BatchMetrics.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 2, metrics, null);

        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, false);
        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        verify(metrics).recordRequestCompleted("test-queue", 2);
        verify(metrics).recordBatchProcessed(eq("test-queue"), eq(2), anyLong(), eq(true));
    }

    /** 异常批次不得计入成功请求数，且 outcome 必须是 failure。 */
    @Test
    void processBatch_failedBatch_reportsFailureMetrics() throws Exception {
        Method throwing = ThrowingService.class.getDeclaredMethod("handle", List.class);
        BatchRequestMetaData throwingMeta = new BatchRequestMetaData(throwing, ThrowingService.class, null,
                "test-queue", 1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 0, 4);
        BatchMetrics metrics = mock(BatchMetrics.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, throwingMeta, new ThrowingService(), 0,
                metrics, null);

        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        verify(metrics, never()).recordRequestCompleted(anyString(), anyInt());
        verify(metrics).recordBatchProcessed(eq("test-queue"), eq(1), anyLong(), eq(false));
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