package io.springperf.web.batch.queue;

import io.springperf.web.batch.common.BatchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class BatchEventTest {

    @Test
    void requestAccessor() {
        BatchEvent event = new BatchEvent();
        BatchRequest<String> req = new BatchRequest<String>() {
        };
        event.request = req;
        assertThat(event.request()).isSameAs(req);
    }

    @Test
    void clearResetsRequestToNull() {
        BatchEvent event = new BatchEvent();
        event.request = new BatchRequest<String>() {
        };
        event.clear();
        assertThat(event.request()).isNull();
    }

    @Test
    void initiallyNull() {
        BatchEvent event = new BatchEvent();
        assertThat(event.request()).isNull();
    }
}
