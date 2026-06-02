package io.springperf.web.core.async.stream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DefaultStreamSenderFactoryTest {

    @Test
    void implementsStreamSenderFactory() {
        assertTrue(new DefaultStreamSenderFactory() instanceof StreamSenderFactory);
    }

    @Test
    void singletonBehavior() {
        DefaultStreamSenderFactory factory = new DefaultStreamSenderFactory();
        assertNotNull(factory);
    }
}