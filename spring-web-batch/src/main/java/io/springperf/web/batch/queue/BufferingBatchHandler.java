package io.springperf.web.batch.queue;

import com.lmax.disruptor.EventHandler;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public class BufferingBatchHandler implements EventHandler<BatchEvent> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Executor executor;
    private final BatchRequestMetaData meta;
    private final Object bean;
    private final int maxBatchSize;

    private List<BatchRequest<?>> buffer = new ArrayList<>();

    public BufferingBatchHandler(Executor executor,
                                 BatchRequestMetaData meta,
                                 Object bean,
                                 int maxBatchSize) {
        this.executor = executor;
        this.meta = meta;
        this.bean = bean;
        this.maxBatchSize = maxBatchSize;
    }

    @Override
    public void onEvent(BatchEvent event, long sequence, boolean endOfBatch) {
        buffer.add(event.request());

        boolean shouldFlush = endOfBatch
                || (maxBatchSize > 0 && buffer.size() >= maxBatchSize);
        if (shouldFlush) {
            flush();
        }
    }

    /**
     * Force-flush any remaining buffer. Called during graceful shutdown after
     * the Disruptor has been drained — at this point the event processor has
     * stopped, so no onEvent() calls will interleave.
     */
    public void flushRemaining() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    private void flush() {
        if (buffer.isEmpty()) return;
        List<BatchRequest<?>> batch = buffer;
        buffer = new ArrayList<>();
        executor.execute(() -> processBatch(batch));
    }

    private void processBatch(List<BatchRequest<?>> batch) {
        try {
            Object[] args = new Object[]{batch};
            meta.batchMethod().invoke(bean, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            handleBatchError(batch, cause);
        } catch (Throwable e) {
            handleBatchError(batch, e);
        }
    }

    private void handleBatchError(List<BatchRequest<?>> batch, Throwable cause) {
        log.error("Error processing batch of {} requests from queue [{}]",
                batch.size(), meta.queueName(), cause);
        for (BatchRequest<?> req : batch) {
            if (!req.isCompleted()) {
                req.setError(cause);
            }
        }
    }
}
