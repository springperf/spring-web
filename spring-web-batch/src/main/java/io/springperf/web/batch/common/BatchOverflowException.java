package io.springperf.web.batch.common;

import io.springperf.web.batch.BatchRegistry;
import io.springperf.web.batch.annotation.BatchMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Thrown when a batch queue's RingBuffer is full and the {@link BatchMapping.Backpressure#THROW}
 * backpressure strategy is configured.
 *
 * <p>Maps to an HTTP 429 (Too Many Requests) response.</p>
 *
 * @since 1.0.0
 * @see BatchMapping.Backpressure
 * @see BatchRegistry
 */
public class BatchOverflowException extends ResponseStatusException {

    public BatchOverflowException(String queueName) {
        super(HttpStatus.TOO_MANY_REQUESTS,
                "Batch queue [" + queueName + "] is full, please retry later");
    }
}