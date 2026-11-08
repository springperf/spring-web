package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1~10ms 小窗口的到期精度实测：{@code batchWindowMillis} 能小到多少，以及每个档位的实际过冲。
 * <p>
 * <b>为什么单独测</b>：窗口越小，攒批越少，收益越依赖「窗口真的按标称值到期」。 若 1ms 窗口实际 10ms 才到期，配置里的 1 就只是名义值，选默认值也就失去意义。
 * {@link BufferingBatchHandlerWindowTest} 只守住 3ms 这一档的回归，这里把所有候选档位并排测出来，作为默认值选择的直接依据。
 * </p>
 * <p>
 * 统计量取中位数（排除偶发调度抖动）与 p90（看尾部），同时打印全部样本，便于判断某个档位是否存在系统性放大。
 * </p>
 * <p>
 * 运行方式：{@code mvn -pl spring-web-batch test -Dperf.enabled=true -Dgroups=perf}， 可用 {@code -Dperf.window=1,2,3,5,10}
 * 覆盖扫描档位，{@code -Dperf.window.samples=30} 覆盖采样次数。
 * </p>
 */
@Tag("perf")
@EnabledIfSystemProperty(named = "perf.enabled", matches = "true")
class BatchWindowPrecisionPerfTest {

    /** 候选窗口档位：默认值（2ms）附近的全部整数档，外加 10ms 作对照。 */
    private static final int[] WINDOWS_MILLIS = parse("perf.window", new int[] { 1, 2, 3, 5, 10 });

    private static final int SAMPLES = Integer.getInteger("perf.window.samples", 30);

    /** 窗口任务可能在测量线程之外执行，逐个用例都要显式关停，避免共享调度器登记数串味。 */
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
        return new BatchRequestMetaData(m, WindowService.class, null, "precision-queue", 1024,
                BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, null, 100, windowMillis, 4);
    }

    @Test
    @DisplayName("实测各档小窗口的到期延迟：1/2/3/5/10ms 的过冲与尾部")
    void expiryPrecisionByWindow() throws Exception {
        System.out.println("[batch-window-precision] 采样 " + SAMPLES + " 次/档");
        for (int windowMillis : WINDOWS_MILLIS) {
            List<Long> samples = new ArrayList<>(SAMPLES);
            for (int i = 0; i < SAMPLES; i++) {
                samples.add(measureOnce(windowMillis));
            }
            samples.sort(Long::compareTo);
            double median = samples.get(samples.size() / 2) / 1e6;
            double p90 = samples.get((int) Math.ceil(samples.size() * 0.9) - 1) / 1e6;
            double min = samples.get(0) / 1e6;
            double max = samples.get(samples.size() - 1) / 1e6;
            System.out
                    .printf("[batch-window-precision] window=%2dms median=%5.2fms p90=%5.2fms min=%5.2fms max=%5.2fms "
                            + "overshoot=%+5.2fms%n", windowMillis, median, p90, min, max, median - windowMillis);
            // 只挡住「小窗口被粗粒度定时器整档放大」这类实现退化，不追求亚毫秒级精度：
            // 中位数过冲超过 4ms 说明该档位已名存实亡
            assertThat(median - windowMillis).as("%dms 窗口的到期中位数不应被放大：samples=%s", windowMillis, samples)
                    .isLessThan(4.0);
        }
    }

    /** 单次测量：投递一个请求，等窗口刷出，返回从投递到刷出的实际耗时（纳秒）。 */
    private long measureOnce(int windowMillis) throws Exception {
        CountDownLatch flushed = new CountDownLatch(1);
        Executor executor = r -> {
            r.run();
            flushed.countDown();
        };
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta(windowMillis), new WindowService(),
                100);
        handlers.add(handler);
        BatchEvent event = new BatchEvent();
        event.request = new BatchRequest<String>() {
        };
        long start = System.nanoTime();
        handler.onEvent(event, 0L, true);
        assertTrue(flushed.await(2, TimeUnit.SECONDS), windowMillis + "ms 窗口应在 2 秒内到期");
        return System.nanoTime() - start;
    }

    private static int[] parse(String key, int[] fallback) {
        String raw = System.getProperty(key);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        String[] parts = raw.split(",");
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i].trim());
        }
        return values;
    }
}
