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
     * 攒批时间窗口（毫秒）。0 = 关闭（默认，保持「endOfBatch 或攒满 maxBatchSize 即刷」的既有语义）。
     * <p>
     * 关闭时批大小完全取决于 RingBuffer 的瞬时积压：消费者线程处理一个事件仅需把请求加入 buffer（纳秒级）， 而 IO 在 bizExecutor
     * 上异步执行、<b>不会</b>阻塞消费者，因此消费者几乎总能追上空队列，每拿到 1 个事件就 endOfBatch 落盘——实测批大小长期在 1~3，{@link #maxBatchSize()} 形同虚设。
     * </p>
     * <p>
     * 开启后，本批第一个事件到达时启动窗口：窗口期内继续累积，直到「攒满 {@code maxBatchSize}」或「窗口到期」才刷。 这样批大小由窗口保证，不再依赖 bizExecutor
     * 偶然饱和；代价是每个批次最多多等一个窗口（单请求延迟上界 ≈ 窗口值）。
     * </p>
     * <p>
     * 建议取 IO 延迟的 1/4 ~ 1/2：窗口远小于 IO 延迟时，攒批收益大于等待成本；反之则应保持 0。
     * </p>
     */
    int batchWindowMillis() default 0;

    /**
     * 业务线程池（bizExecutor）的最大并发线程数；Disruptor 消费者线程是独立的另一个，不在本数之内。 默认 CPU 核数。
     * <p>
     * 该值用于复用多核，通常取核数级别即可，不应超配——详见 {@link #batchWindowMillis()} 对「consumerSize 与批大小对冲」的说明。
     * </p>
     */
    int consumerSize() default -1;

    enum WaitStrategy {
        YIELDING, BLOCKING, SLEEPING, BUSY_SPIN
    }

    enum Backpressure {
        BLOCK, DROP, THROW
    }
}
