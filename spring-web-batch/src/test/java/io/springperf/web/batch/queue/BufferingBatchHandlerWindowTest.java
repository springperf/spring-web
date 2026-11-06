package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 攒批时间窗口（{@link BatchMapping#batchWindowMillis()}）的行为验证。
 * <p>
 * 窗口存在的理由：IO 在 bizExecutor 上异步执行、不阻塞 Disruptor 消费者线程， 因此消费者总能追上空队列、每拿到 1 个事件就 {@code endOfBatch} 落盘——关闭窗口时实测批大小长期在
 * 1~3，{@code maxBatchSize} 形同虚设。 窗口把刷出时机改为「攒满 maxBatchSize 或窗口到期」，批大小才由配置保证。
 * </p>
 */
@SuppressWarnings("unchecked")
class BufferingBatchHandlerWindowTest {

    /**
     * 窗口调度器是全进程共享的静态资源：每个用例结束都必须 closeWindow，否则本类的登记数会跨用例累积， 干扰「调度器何时关停」的断言（也是早期测试互相污染的原因）。
     */
    private final List<BufferingBatchHandler> handlers = new ArrayList<>();

    @AfterEach
    void closeWindows() {
        handlers.forEach(BufferingBatchHandler::closeWindow);
        handlers.clear();
    }

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

    private BufferingBatchHandler newHandler(Executor executor, int windowMillis, int maxBatchSize) throws Exception {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(windowMillis), new WindowService(),
                maxBatchSize);
        handlers.add(handler);
        return handler;
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
        BufferingBatchHandler handler = newHandler(executor, 0, 0);

        assertThat(handler.isWindowEnabled()).isFalse();
        handler.onEvent(newEvent(request()), 0L, true);

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("窗口开启：单个事件 + endOfBatch 不立即刷，须等窗口到期")
    void windowEnabled_singleEventDoesNotFlushOnEndOfBatch() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = newHandler(executor, 50, 100);

        assertThat(handler.isWindowEnabled()).isTrue();
        // 窗口模式下 endOfBatch 几乎恒为 true；若响应它，窗口永远等不到第二个请求
        handler.onEvent(newEvent(request()), 0L, true);

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("窗口开启：攒满 maxBatchSize 立即刷，不等窗口")
    void windowEnabled_maxBatchSizeFlushesImmediately() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = newHandler(executor, 10_000, 3);

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
        BufferingBatchHandler handler = newHandler(executor, 30, 100);

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
        BufferingBatchHandler handler = newHandler(executor, 10, 100);

        handler.flushOnWindow();

        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("flushRemaining 仍能冲刷窗口模式下残余的缓冲")
    void windowEnabled_flushRemainingStillWorks() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = newHandler(executor, 60_000, 100);

        handler.onEvent(newEvent(request()), 0L, true);
        verify(executor, never()).execute(any(Runnable.class));

        handler.flushRemaining();
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("closeWindow 之后窗口不再触发新提交（停机安全）")
    void closeWindow_stopsFurtherSubmissions() throws Exception {
        Executor executor = mock(Executor.class);
        BufferingBatchHandler handler = newHandler(executor, 60_000, 100);

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
        BufferingBatchHandler handler = newHandler(executor, 0, 0);
        handler.onEvent(newEvent(request()), 0L, true);
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    /**
     * 回归：早期实现里 closeWindow 无条件对全局计数减一，无窗口队列（从未登记过调度器） 在停机时会把其他队列仍在使用中的共享调度器 {@code shutdownNow} 掉。
     */
    @Test
    @DisplayName("无窗口队列的 closeWindow 不得关停其他队列仍在使用的窗口调度器")
    void closeWindow_withoutRegistration_doesNotCancelOtherQueuesScheduler() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        Executor executor = r -> {
            submitted.incrementAndGet();
            r.run();
        };
        BufferingBatchHandler windowed = newHandler(executor, 80, 100);
        BufferingBatchHandler noWindow = newHandler(executor, 0, 100);

        // 有窗口的 handler 排定窗口任务；无窗口 handler 从未登记调度器
        windowed.onEvent(newEvent(request()), 0L, true);
        assertThat(submitted.get()).isZero();

        // 模拟无窗口队列停机：真实路径是 shutdown() → flushRemaining()（空）→ closeWindow()
        noWindow.closeWindow();

        for (int i = 0; i < 100 && submitted.get() == 0; i++) {
            Thread.sleep(20);
        }
        assertThat(submitted.get()).as("其他队列的窗口任务不应被无窗口队列的停机关停").isEqualTo(1);
    }

    /** 回归：重复 closeWindow 不得把登记数扣成负数，从而关掉共享调度器。 */
    @Test
    @DisplayName("重复 closeWindow 不破坏共享调度器：后续队列仍能走窗口刷出")
    void closeWindow_twice_doesNotBreakSchedulerForOtherHandlers() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        Executor executor = r -> {
            submitted.incrementAndGet();
            r.run();
        };
        BufferingBatchHandler first = newHandler(executor, 40, 100);
        first.onEvent(newEvent(request()), 0L, true);
        first.flushOnWindow();
        first.closeWindow();
        first.closeWindow();

        BufferingBatchHandler second = newHandler(executor, 40, 100);
        second.onEvent(newEvent(request()), 0L, true);

        for (int i = 0; i < 100 && submitted.get() < 2; i++) {
            Thread.sleep(20);
        }
        assertThat(submitted.get()).as("后续队列的窗口任务仍应被执行").isEqualTo(2);
    }
}
