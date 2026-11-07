package io.springperf.web.batch.queue;

import io.springperf.web.batch.common.BatchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

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
        // handler 的唯一职责是把原始异常写入请求，必须校验错误对象本身
        assertThat(req.hasResult()).isTrue();
        assertThat(req.getResult()).isSameAs(ex);
    }

    @Test
    void handleEventException_skipsAlreadyCompletedRequest() {
        BatchRequest<String> req = new BatchRequest<String>() {
        };
        req.setResult("ok");
        BatchEvent event = new BatchEvent();
        event.request = req;

        handler.handleEventException(new RuntimeException("late error"), 1L, event);

        // 已完成的请求不得被错误覆盖——这是 "skip" 的真实语义
        assertThat(req.getResult()).isEqualTo("ok");
        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void handleEventException_handlesNullEvent() {
        assertDoesNotThrow(() -> handler.handleEventException(new RuntimeException("null event"), 1L, null));
    }

    @Test
    void handleEventException_handlesNullRequest() {
        BatchEvent event = new BatchEvent();
        assertDoesNotThrow(() -> handler.handleEventException(new RuntimeException("null req"), 1L, event));
    }

    @Test
    void handleOnStartException_doesNotThrow() {
        assertDoesNotThrow(() -> handler.handleOnStartException(new RuntimeException("start fail")));
    }

    @Test
    void handleOnShutdownException_doesNotThrow() {
        assertDoesNotThrow(() -> handler.handleOnShutdownException(new RuntimeException("shutdown fail")));
    }
}
