package io.springperf.web.batch.queue;

import com.lmax.disruptor.EventTranslatorOneArg;
import io.springperf.web.batch.common.BatchRequest;

public class BatchEventTranslator implements EventTranslatorOneArg<BatchEvent, BatchRequest<?>> {

    @Override
    public void translateTo(BatchEvent event, long sequence, BatchRequest<?> request) {
        event.request = request;
    }
}