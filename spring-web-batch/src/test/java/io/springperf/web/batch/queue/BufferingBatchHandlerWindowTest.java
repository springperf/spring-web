package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** 记录每个实际提交的批次内容，用于断言批大小与请求集合。 */
    static class RecordingService {
        final List<List<?>> batches = new CopyOnWriteArrayList<>();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        public void handle(List<? extends BatchRequest<?>> batch) {
            batches.add(new ArrayList<>(batch));
            for (BatchRequest request : batch) {
                request.setResult("ok");
            }
        }
    }

    private static BatchRequestMetaData meta(int windowMillis) throws Exception {
        Method m = WindowService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, WindowService.class, null, "win-queue", 1024,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 100, windowMillis, 4);
    }

    private static BatchRequestMetaData recordingMeta(int windowMillis) throws Exception {
        Method m = RecordingService.class.getDeclaredMethod("handle", List.class);
        return new BatchRequestMetaData(m, RecordingService.class, null, "win-record", 1024,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 100, windowMillis, 4);
    }

    private BufferingBatchHandler newHandler(Executor executor, int windowMillis, int maxBatchSize) throws Exception {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(windowMillis), new WindowService(),
                maxBatchSize);
        handlers.add(handler);
        return handler;
    }

    private BufferingBatchHandler newRecordingHandler(Executor executor, int windowMillis, int maxBatchSize,
            RecordingService service) throws Exception {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, recordingMeta(windowMillis), service,
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
    @DisplayName("窗口开启：攒满 maxBatchSize 立即刷，且整批恰好包含这 3 个请求")
    void windowEnabled_maxBatchSizeFlushesImmediately() throws Exception {
        Executor executor = mock(Executor.class);
        RecordingService service = new RecordingService();
        BufferingBatchHandler handler = newRecordingHandler(executor, 10_000, 3, service);

        BatchRequest<String> r1 = request();
        BatchRequest<String> r2 = request();
        BatchRequest<String> r3 = request();
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);
        verify(executor, never()).execute(any(Runnable.class));

        handler.onEvent(newEvent(r3), 2L, false);
        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(1)).execute(captor.capture());
        captor.getValue().run();

        assertThat(service.batches).hasSize(1);
        assertThat(service.batches.get(0)).isEqualTo(List.of(r1, r2, r3));
    }

    @Test
    @DisplayName("窗口开启：到期后把窗口期内累积的 5 个请求一次性提交")
    void windowEnabled_flushesAccumulatedBatchOnExpiry() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        Executor executor = r -> {
            submitted.incrementAndGet();
            r.run();
        };
        RecordingService service = new RecordingService();
        BufferingBatchHandler handler = newRecordingHandler(executor, 30, 100, service);

        // 模拟窗口期内到达 5 个请求（消费者线程视角：逐条 onEvent）
        List<BatchRequest<String>> requests = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BatchRequest<String> req = request();
            requests.add(req);
            handler.onEvent(newEvent(req), i, i == 4);
        }
        assertThat(submitted.get()).as("窗口期内不应提前提交").isZero();

        // 窗口到期
        handler.flushOnWindow();
        assertThat(submitted.get()).as("窗口到期应把 5 个请求作为一批提交").isEqualTo(1);
        assertThat(service.batches).hasSize(1);
        assertThat(service.batches.get(0)).isEqualTo(requests);
        requests.forEach(req -> assertThat(req.getResult()).isEqualTo("ok"));
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

    /**
     * 回归：早期实现里 closeWindow 无条件对全局计数减一，无窗口队列（从未登记过调度器） 在停机时会把其他队列仍在使用中的共享调度器 {@code shutdownNow} 掉。
     */
    @Test
    @DisplayName("无窗口队列的 closeWindow 不得关停其他队列仍在使用的窗口调度器")
    void closeWindow_withoutRegistration_doesNotCancelOtherQueuesScheduler() throws Exception {
        AtomicInteger submitted = new AtomicInteger();
        CountDownLatch windowFlushed = new CountDownLatch(1);
        Executor executor = r -> {
            submitted.incrementAndGet();
            r.run();
            windowFlushed.countDown();
        };
        BufferingBatchHandler windowed = newHandler(executor, 80, 100);
        BufferingBatchHandler noWindow = newHandler(executor, 0, 100);

        // 有窗口的 handler 排定窗口任务；无窗口 handler 从未登记调度器
        windowed.onEvent(newEvent(request()), 0L, true);
        assertThat(submitted.get()).isZero();

        // 模拟无窗口队列停机：真实路径是 shutdown() → flushRemaining()（空）→ closeWindow()
        noWindow.closeWindow();

        assertTrue(windowFlushed.await(2, TimeUnit.SECONDS),
                "其他队列的窗口任务不应被无窗口队列的停机关停");
        assertThat(submitted).hasValue(1);
    }

    /** 回归：重复 closeWindow 不得把登记数扣成负数，从而关掉共享调度器。 */
    @Test
    @DisplayName("重复 closeWindow 不破坏共享调度器：后续队列仍能走窗口刷出")
    void closeWindow_twice_doesNotBreakSchedulerForOtherHandlers() throws Exception {
        Executor firstExecutor = Runnable::run;
        BufferingBatchHandler first = newHandler(firstExecutor, 40, 100);
        first.onEvent(newEvent(request()), 0L, true);
        first.flushOnWindow();
        first.closeWindow();
        first.closeWindow();

        AtomicInteger secondSubmitted = new AtomicInteger();
        CountDownLatch secondFlushed = new CountDownLatch(1);
        Executor secondExecutor = r -> {
            secondSubmitted.incrementAndGet();
            r.run();
            secondFlushed.countDown();
        };
        BufferingBatchHandler second = newHandler(secondExecutor, 40, 100);
        second.onEvent(newEvent(request()), 0L, true);

        // 用独立计数 + 闩锁，排除第一个 handler 的提交对断言的干扰
        assertTrue(secondFlushed.await(2, TimeUnit.SECONDS),
                "重复 closeWindow 后，后续队列的窗口任务仍应被执行");
        assertThat(secondSubmitted).hasValue(1);
    }

    /**
     * 精度回归：{@code ScheduledThreadPoolExecutor} 内部基于 {@code LockSupport.parkNanos}，
     * 在粗粒度系统定时器上会把 1~10ms 的延迟统一放大到约 15ms，使
     * {@code batchWindowMillis=3/5} 名存实亡。改用 {@code Timer} 后 3ms 实测约 3ms。
     * <p>
     * 取多次采样的中位数，排除偶发线程调度抖动；阈值 12ms 足以区分「约 3ms」与「约 15ms」。
     * 中位数会打印到测试输出，便于对照不同环境下的实际到期精度。
     * </p>
     */
    @Test
    @DisplayName("小窗口精度回归：3ms 窗口不应被放大到系统定时器粒度（约 15ms）")
    void windowEnabled_smallWindowIsNotQuantizedToTimerTick() throws Exception {
        List<Long> elapsedMillis = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            CountDownLatch flushed = new CountDownLatch(1);
            Executor executor = r -> {
                r.run();
                flushed.countDown();
            };
            BufferingBatchHandler handler = newHandler(executor, 3, 100);
            long start = System.nanoTime();
            handler.onEvent(newEvent(request()), 0L, true);
            assertTrue(flushed.await(1, TimeUnit.SECONDS), "窗口任务应在 1 秒内到期");
            elapsedMillis.add((System.nanoTime() - start) / 1_000_000L);
            handler.closeWindow();
        }
        elapsedMillis.sort(Long::compareTo);
        long median = elapsedMillis.get(elapsedMillis.size() / 2);
        System.out.println("[batch-window] 3ms window: median expiry " + median + "ms, samples " + elapsedMillis);
        assertThat(median).as("3ms 窗口到期中位数（旧实现约 15ms）：%s", elapsedMillis)
                .isLessThan(12L);
    }

    /**
     * Timer 线程不捕获任务异常：任一未捕获异常都会终止整个共享调度线程，
     * 导致其他队列的窗口任务永不到期。TimerTask#run 必须自行收敛 {@code Throwable}。
     * <p>
     * 若收敛失效，后续队列的 {@code schedule()} 会抛 {@code IllegalStateException}
     * 并走「立即刷出」兜底，因此除了断言任务最终触发，还要断言它确实等过了窗口期，
     * 否则无法与「调度线程已死」区分开。
     * </p>
     */
    @Test
    @DisplayName("窗口任务抛异常不得终止共享调度线程，后续队列仍按期刷出")
    void windowTaskFailure_doesNotKillSharedScheduler() throws Exception {
        BufferingBatchHandler failing = newHandler(r -> {
            throw new IllegalStateException("simulated executor failure");
        }, 20, 100);
        failing.onEvent(newEvent(request()), 0L, true);

        CountDownLatch later = new CountDownLatch(1);
        BufferingBatchHandler healthy = newHandler(r -> {
            r.run();
            later.countDown();
        }, 20, 100);
        long start = System.nanoTime();
        healthy.onEvent(newEvent(request()), 0L, true);

        assertTrue(later.await(2, TimeUnit.SECONDS),
                "窗口任务异常后共享调度线程仍应存活，后续队列的窗口任务应正常到期");
        assertThat((System.nanoTime() - start) / 1_000_000L)
                .as("后续队列应等满窗口，而不是因调度器失效被立即刷出")
                .isGreaterThanOrEqualTo(10L);
    }
}
