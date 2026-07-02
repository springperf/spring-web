package io.springperf.web.batch.queue;

import io.springperf.web.batch.common.BatchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class BatchExceptionHandlerTest {

    private final BatchExceptionHandler handler = new BatchExceptionHandler("test-queue");

    @Test
    void handleEventException_failsRequestIfNotCompleted() {
        BatchRequest<String> req = new BatchRequest<String>() {
        };
        BatchEvent event = new BatchEvent();
        event.request = req;
        Throwable ex = new RuntimeException("consumer error");

        handler.handleEventException(ex, 1L, event);

        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void handleEventException_skipsAlreadyCompletedRequest() {
        BatchRequest<String> req = new BatchRequest<String>() {
        };
        req.setResult("ok");
        BatchEvent event = new BatchEvent();
        event.request = req;

        handler.handleEventException(new RuntimeException("late error"), 1L, event);

        assertThat(req.isCompleted()).isTrue(); // still completed from setResult
    }

    @Test
    void handleEventException_handlesNullEvent() {
        // Should not throw NPE
        handler.handleEventException(new RuntimeException("null event"), 1L, null);
    }

    @Test
    void handleEventException_handlesNullRequest() {
        BatchEvent event = new BatchEvent();
        handler.handleEventException(new RuntimeException("null req"), 1L, event);
        // Should not throw NPE
    }

    @Test
    void handleOnStartException_doesNotThrow() {
        handler.handleOnStartException(new RuntimeException("start fail"));
    }

    @Test
    void handleOnShutdownException_doesNotThrow() {
        handler.handleOnShutdownException(new RuntimeException("shutdown fail"));
    }
}
