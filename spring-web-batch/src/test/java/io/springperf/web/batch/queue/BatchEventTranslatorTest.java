package io.springperf.web.batch.queue;

import io.springperf.web.batch.common.BatchRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class BatchEventTranslatorTest {

    @Test
    void translateToSetsRequest() {
        BatchEvent event = new BatchEvent();
        BatchRequest<String> req = new BatchRequest<String>() {
        };

        BatchEventTranslator translator = new BatchEventTranslator();
        translator.translateTo(event, 42L, req);

        assertThat(event.request()).isSameAs(req);
    }

    @Test
    void translateToReplacesPreviousRequest() {
        BatchEvent event = new BatchEvent();
        event.request = new BatchRequest<String>() {
        };
        BatchRequest<String> newReq = new BatchRequest<String>() {
        };

        BatchEventTranslator translator = new BatchEventTranslator();
        translator.translateTo(event, 1L, newReq);

        assertThat(event.request()).isSameAs(newReq);
    }
}
