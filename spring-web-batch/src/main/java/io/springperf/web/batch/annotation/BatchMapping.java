package io.springperf.web.batch.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BatchMapping {

    int ringBufferSize() default 4096;

    WaitStrategy waitStrategy() default WaitStrategy.BLOCKING;

    Backpressure backpressure() default Backpressure.BLOCK;

    String method() default "";

    /** 单次批处理最大请求数，达到该值时触发批量处理。Disruptor endOfBatch 信号也会触发。 */
    int maxBatchSize() default 100;

    /**
     * 攒批时间窗口（毫秒），默认 5ms；设为 0 可显式关闭。
     * <p>
     * 开启后，本批第一个事件到达即启动窗口：窗口期内继续累积，直到「攒满 {@code maxBatchSize}」或「窗口到期」才刷。 批大小由窗口保证，不再依赖
     * bizExecutor 偶然饱和；代价是每个批次最多多等一个窗口（单请求延迟上界 ≈ 窗口值）。
     * </p>
     * <p>
     * 关闭时刷出时机回到 Disruptor {@code endOfBatch}，批大小取决于 RingBuffer 的瞬时积压：消费者把请求加入 buffer 即可返回，IO 在
     * bizExecutor 上异步执行、<b>不会</b>阻塞消费者，因此消费者几乎总能追上空队列，{@link #maxBatchSize()} 很难达到。
     * </p>
     * <p>
     * 5ms 适用于多数 DB / RPC 场景：实测（并发 100，下游 IO 5~50ms）批大小可达 48~100、IO 次数降 4~5 倍，吞吐不低于不开窗口。
     * 低负载且下游 IO 很快（&lt; 5ms）时等待成本占比更高，可设为 0 或 2~3ms；下游 IO 很慢时也不宜盲目加大窗口，请先压测确认。
     * 取值细节见批处理文档。
     * </p>
     */
    int batchWindowMillis() default 5;

    /**
     * 业务线程池（bizExecutor）的最大并发线程数；Disruptor 消费者线程是独立的另一个，不在本数之内。 默认 CPU 核数。
     * <p>
     * 该值用于复用多核，通常取核数级别即可，不应超配。显式关闭攒批窗口时，调大它会进一步压低批大小（消费更快 → RingBuffer 积压更少）；
     * 默认 5ms 窗口下批大小由窗口保证，两者不再对冲，详见 {@link #batchWindowMillis()}。
     * </p>
     */
    int consumerSize() default -1;

    enum WaitStrategy { YIELDING, BLOCKING, SLEEPING, BUSY_SPIN }

    enum Backpressure { BLOCK, DROP, THROW }
}
