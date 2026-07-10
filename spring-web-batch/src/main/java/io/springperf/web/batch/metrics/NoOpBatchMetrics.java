package io.springperf.web.batch.metrics;

/**
 * No-operation {@link BatchMetrics} implementation.
 * <p>
 * Used as the default when no metrics integration is configured.
 * All methods are empty — no overhead when metrics are not needed.
 * </p>
 */
public final class NoOpBatchMetrics implements BatchMetrics {

    public static final NoOpBatchMetrics INSTANCE = new NoOpBatchMetrics();

    @Override
    public void recordEnqueue(String queueName, boolean success) {
    }

    @Override
    public void recordDrop(String queueName) {
    }

    @Override
    public void recordOverflow(String queueName) {
    }

    @Override
    public void recordBatchProcessed(String queueName, int batchSize, long durationNanos, boolean success) {
    }

    @Override
    public void recordRequestCompleted(String queueName, int count) {
    }

    @Override
    public void reportQueueCapacity(String queueName, int remaining, int total) {
    }
}