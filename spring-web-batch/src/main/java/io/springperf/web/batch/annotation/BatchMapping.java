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

    /** 最大并发处理线程数（包括 Disruptor 消费者线程在内）。默认 CPU 核数。 */
    int consumerSize() default -1;

    enum WaitStrategy { YIELDING, BLOCKING, SLEEPING, BUSY_SPIN }

    enum Backpressure { BLOCK, DROP, THROW }
}
