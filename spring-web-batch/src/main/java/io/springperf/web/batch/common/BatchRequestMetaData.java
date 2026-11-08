package io.springperf.web.batch.common;

import io.springperf.web.batch.annotation.BatchMapping;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public class BatchRequestMetaData {

    private final Method batchMethod;
    private final Class<?> beanType;
    private final Class<? extends BatchRequest<?>> requestType;
    private final String queueName;
    private final int ringBufferSize;
    private final BatchMapping.WaitStrategy waitStrategy;
    private final BatchMapping.Backpressure backpressure;
    private final Constructor<?> singleMethodCtor;
    private final int maxBatchSize;
    private final int batchWindowMillis;
    private final int consumerSize;

    /**
     * 兼容重载：等价于显式指定 {@code batchWindowMillis=0}（关闭攒批窗口）。
     * <p>
     * 注解未声明窗口时走 {@link BatchMapping#batchWindowMillis()} 的默认值（5ms），由 {@link BatchScanner} 使用全参构造传入；
     * 本重载供「明确不需要窗口」的场景（如手工构造队列的测试与对照实验）使用。
     * </p>
     */
    public BatchRequestMetaData(Method batchMethod, Class<?> beanType, Class<? extends BatchRequest<?>> requestType,
            String queueName, int ringBufferSize, BatchMapping.WaitStrategy waitStrategy,
            BatchMapping.Backpressure backpressure, Constructor<?> singleMethodCtor, int maxBatchSize,
            int consumerSize) {
        this(batchMethod, beanType, requestType, queueName, ringBufferSize, waitStrategy, backpressure,
                singleMethodCtor, maxBatchSize, 0, consumerSize);
    }

    public BatchRequestMetaData(Method batchMethod, Class<?> beanType, Class<? extends BatchRequest<?>> requestType,
            String queueName, int ringBufferSize, BatchMapping.WaitStrategy waitStrategy,
            BatchMapping.Backpressure backpressure, Constructor<?> singleMethodCtor, int maxBatchSize,
            int batchWindowMillis, int consumerSize) {
        this.batchMethod = batchMethod;
        this.beanType = beanType;
        this.requestType = requestType;
        this.queueName = queueName;
        this.ringBufferSize = ringBufferSize;
        this.waitStrategy = waitStrategy;
        this.backpressure = backpressure;
        this.singleMethodCtor = singleMethodCtor;
        this.maxBatchSize = maxBatchSize;
        this.batchWindowMillis = batchWindowMillis;
        this.consumerSize = consumerSize;
    }

    public Method batchMethod() {
        return batchMethod;
    }

    public Class<?> beanType() {
        return beanType;
    }

    public Class<? extends BatchRequest<?>> requestType() {
        return requestType;
    }

    public String queueName() {
        return queueName;
    }

    public int ringBufferSize() {
        return ringBufferSize;
    }

    public BatchMapping.WaitStrategy waitStrategy() {
        return waitStrategy;
    }

    public BatchMapping.Backpressure backpressure() {
        return backpressure;
    }

    public Constructor<?> singleMethodCtor() {
        return singleMethodCtor;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    /**
     * 攒批时间窗口（毫秒），0 = 显式关闭；注解未声明时为 5ms。
     *
     * @see BatchMapping#batchWindowMillis()
     */
    public int batchWindowMillis() {
        return batchWindowMillis;
    }

    public int consumerSize() {
        return consumerSize;
    }
}
