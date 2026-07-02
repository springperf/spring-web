package io.springperf.web.batch.queue;

import io.springperf.web.batch.common.BatchRequest;

public class BatchEvent {
    BatchRequest<?> request;

    public void clear() {
        this.request = null;
    }

    public BatchRequest<?> request() { return request; }
}