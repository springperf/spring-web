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
    private final int consumerSize;

    public BatchRequestMetaData(Method batchMethod,
                                Class<?> beanType,
                                Class<? extends BatchRequest<?>> requestType,
                                String queueName,
                                int ringBufferSize,
                                BatchMapping.WaitStrategy waitStrategy,
                                BatchMapping.Backpressure backpressure,
                                Constructor<?> singleMethodCtor,
                                int maxBatchSize,
                                int consumerSize) {
        this.batchMethod = batchMethod;
        this.beanType = beanType;
        this.requestType = requestType;
        this.queueName = queueName;
        this.ringBufferSize = ringBufferSize;
        this.waitStrategy = waitStrategy;
        this.backpressure = backpressure;
        this.singleMethodCtor = singleMethodCtor;
        this.maxBatchSize = maxBatchSize;
        this.consumerSize = consumerSize;
    }

    public Method batchMethod() { return batchMethod; }
    public Class<?> beanType() { return beanType; }
    public Class<? extends BatchRequest<?>> requestType() { return requestType; }
    public String queueName() { return queueName; }
    public int ringBufferSize() { return ringBufferSize; }
    public BatchMapping.WaitStrategy waitStrategy() { return waitStrategy; }
    public BatchMapping.Backpressure backpressure() { return backpressure; }
    public Constructor<?> singleMethodCtor() { return singleMethodCtor; }
    public int maxBatchSize() { return maxBatchSize; }
    public int consumerSize() { return consumerSize; }
}