package io.springperf.web.batch.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class BatchOverflowExceptionTest {

    @Test
    void httpStatusIs429() {
        BatchOverflowException ex = new BatchOverflowException("test-queue");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void messageContainsQueueName() {
        BatchOverflowException ex = new BatchOverflowException("my-queue");
        assertThat(ex.getReason()).contains("my-queue");
    }

    @Test
    void isResponseStatusException() {
        BatchOverflowException ex = new BatchOverflowException("q");
        assertThat(ex).isInstanceOf(ResponseStatusException.class);
    }
}
