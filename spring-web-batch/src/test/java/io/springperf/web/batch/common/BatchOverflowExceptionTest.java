package io.springperf.web.batch.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class BatchOverflowExceptionTest {

    @Test
    void httpStatusIs429() {
        BatchOverflowException ex = new BatchOverflowException("test-queue");
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void reasonIncludesQueueNameAndRetryHint() {
        BatchOverflowException ex = new BatchOverflowException("my-queue");
        assertThat(ex.getReason()).isEqualTo("Batch queue [my-queue] is full, please retry later");
    }
}
