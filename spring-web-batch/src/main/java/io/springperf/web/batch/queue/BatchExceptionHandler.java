package io.springperf.web.batch.queue;

import com.lmax.disruptor.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class BatchExceptionHandler implements ExceptionHandler<BatchEvent> {

    private final String queueName;

    public BatchExceptionHandler(String queueName) {
        this.queueName = queueName;
    }

    @Override
    public void handleEventException(Throwable ex, long sequence, BatchEvent event) {
        log.error("Disruptor queue [{}] consumer error processing sequence {}",
                queueName, sequence, ex);
        // Fail the request if possible so the client doesn't hang
        if (event != null && event.request() != null && !event.request().isCompleted()) {
            event.request().setError(ex);
        }
    }

    @Override
    public void handleOnStartException(Throwable ex) {
        log.error("Disruptor queue [{}] consumer failed to start", queueName, ex);
    }

    @Override
    public void handleOnShutdownException(Throwable ex) {
        log.warn("Disruptor queue [{}] consumer error during shutdown", queueName, ex);
    }
}
