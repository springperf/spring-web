package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 攒批时间窗口（{@link BatchMapping#batchWindowMillis()}）的行为验证。
 * <p>
 * 窗口存在的理由：IO 在 bizExecutor 上异步执行、不阻塞 Disruptor 消费者线程， 因此消费者总能追上空队列、每拿到 1 个事件就 {@code endOfBatch} 落盘——关闭窗口时实测批大小长期在
 * 1~3，{@code maxBatchSize} 形同虚设。 窗口把刷出时机改为「攒满 maxBatchSize 或窗口到期」，批大小才由配置保证。
 * </p>
 */
@SuppressWarnings("unchecked")
class BufferingBatchHandlerWindowTest {

    static class WindowService {
        @SuppressWarnings("unused")
        public void handle(List<? extends BatchRequest<?>> batch) {
        }
    }

    private static BatchRequestMetaData meta(int windowMillis) throws Exception {
        Method m = WindowService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, WindowService.class, null, "win-queue", 1024,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 100, windowMillis, 4);
    }

    private static BatchEvent newEvent(BatchRequest<?> request) {
        BatchEvent event = new BatchEvent();
        event.request = request;
        return event;
    }

    private static BatchRequest<String> request() {
        return new BatchRequest<String>() {
        };
    }

    @Test
    @DisplayName("窗口关闭（默认）：endOfBatch 立即刷，与历史行为一致")
    void windowDisabled_endOfBatchFlushesImmediately() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(0), new WindowService(), 0);

        assertThat(handler.isWindowEnabled()).isFalse();
        handler.onEvent(newEvent(request()), 0L, true);

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("窗口开启：单个事件 + endOfBatch 不立即刷，须等窗口到期")
    void windowEnabled_singleEventDoesNotFlushOnEndOfBatch() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(50), new WindowService(), 100);

        assertThat(handler.isWindowEnabled()).isTrue();
        // 窗口模式下 endOfBatch 几乎恒为 true；若响应它，窗口永远等不到第二个请求
        handler.onEvent(newEvent(request()), 0L, true);

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("窗口开启：攒满 maxBatchSize 立即刷，不等窗口")
    void windowEnabled_maxBatchSizeFlushesImmediately() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(10_000), new WindowService(), 3);

        handler.onEvent(newEvent(request()), 0L, false);
        handler.onEvent(newEvent(request()), 1L, false);
        verify(executor, never()).execute(any(Runnable.class));

        handler.onEvent(newEvent(request()), 2L, false);
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("窗口开启：到期后把窗口期内累积的整批一次性提交")
    void windowEnabled_flushesAccumulatedBatchOnExpiry() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        Executor executor = r -> {
            submitted.incrementAndGet();
            r.run();
        };
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(30), new WindowService(), 100);

        // 模拟窗口期内到达 5 个请求（消费者线程视角：逐条 onEvent）
        for (int i = 0; i < 5; i++) {
            handler.onEvent(newEvent(request()), i, i == 4);
        }
        assertThat(submitted.get()).as("窗口期内不应提前提交").isZero();

        // 窗口到期
        handler.flushOnWindow();
        assertThat(submitted.get()).as("窗口到期应把 5 个请求作为一批提交").isEqualTo(1);
    }

    @Test
    @DisplayName("窗口开启：到期时缓冲为空则无事发生")
    void windowEnabled_expiryWithEmptyBufferIsNoop() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(10), new WindowService(), 100);

        handler.flushOnWindow();

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("flushRemaining 仍能冲刷窗口模式下残余的缓冲")
    void windowEnabled_flushRemainingStillWorks() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(60_000), new WindowService(), 100);

        handler.onEvent(newEvent(request()), 0L, true);
        verify(executor, never()).execute(any(Runnable.class));

        handler.flushRemaining();
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("closeWindow 之后窗口不再触发新提交（停机安全）")
    void closeWindow_stopsFurtherSubmissions() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(60_000), new WindowService(), 100);

        handler.closeWindow();
        // closeWindow 置位后，即便再来事件也不再启动窗口；endOfBatch 分支同样已被窗口模式旁路，
        // 因此不会产生任何提交——停机后迟到的请求由 DeferredResult 超时兜底
        handler.onEvent(newEvent(request()), 0L, true);

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("窗口与 maxBatchSize 同为 0 时不死等：endOfBatch 仍会刷")
    void windowEnabled_zeroMaxBatchSize_stillFlushesOnEndOfBatch() throws Exception {
        Executor executor = mock(Executor.class);
        // maxBatchSize=0 表示禁用攒满刷；此时若窗口也不兜底就会永久挂起，故 endOfBatch 必须仍能刷
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(0), new WindowService(), 0);
        handler.onEvent(newEvent(request()), 0L, true);
        verify(executor, times(1)).execute(any(Runnable.class));
    }
}
