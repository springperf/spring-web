package io.springperf.web.batch.metrics;

/**
 * Batch processing metrics collector.
 * <p>
 * SPI for collecting observability data from the batch processing pipeline.
 * The default implementation ({@link NoOpBatchMetrics}) does nothing;
 * framework integrators can provide a Micrometer-based or custom implementation.
 * </p>
 */
public interface BatchMetrics {

    /**
     * A single request was enqueued into the ring buffer.
     *
     * @param queueName queue identifier
     * @param success   whether the enqueue succeeded
     */
    void recordEnqueue(String queueName, boolean success);

    /**
     * A request was dropped because the ring buffer was full
     * and the backpressure strategy is {@code DROP}.
     *
     * @param queueName queue identifier
     */
    void recordDrop(String queueName);

    /**
     * A request caused a {@code BatchOverflowException} because the
     * ring buffer was full and the backpressure strategy is {@code THROW}.
     *
     * @param queueName queue identifier
     */
    void recordOverflow(String queueName);

    /**
     * A batch of requests has been processed (successfully or failed).
     *
     * @param queueName    queue identifier
     * @param batchSize    number of requests in this batch
     * @param durationNanos elapsed processing time in nanoseconds
     * @param success      whether the batch handler completed without throwing
     */
    void recordBatchProcessed(String queueName, int batchSize, long durationNanos, boolean success);

    /**
     * Requests completed via batch processing.
     *
     * @param queueName queue identifier
     * @param count     number of requests completed in this batch
     */
    void recordRequestCompleted(String queueName, int count);

    /**
     * Report the current remaining capacity of the ring buffer.
     *
     * @param queueName       queue identifier
     * @param remaining       free slots in the ring buffer
     * @param total           total capacity of the ring buffer
     */
    void reportQueueCapacity(String queueName, int remaining, int total);
}