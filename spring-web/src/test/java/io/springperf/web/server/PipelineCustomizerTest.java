package io.springperf.web.server;

import io.netty.channel.ChannelHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PipelineCustomizerTest {

    @Test
    void initiallyEmpty() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        assertFalse(customizer.hasHandlers());
        assertTrue(customizer.getBeforeAggregatorHandlers().isEmpty());
        assertTrue(customizer.getAfterAggregatorHandlers().isEmpty());
    }

    @Test
    void addBeforeAggregator_returnsSelf() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        assertSame(customizer, customizer.addBeforeAggregator(mockHandler()));
    }

    @Test
    void addAfterAggregator_returnsSelf() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        assertSame(customizer, customizer.addAfterAggregator(mockHandler()));
    }

    @Test
    void getBeforeAggregatorHandlers_returnsAdded() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        ChannelHandler h1 = mockHandler();
        ChannelHandler h2 = mockHandler();
        customizer.addBeforeAggregator(h1);
        customizer.addBeforeAggregator(h2);
        List<ChannelHandler> list = customizer.getBeforeAggregatorHandlers();
        assertEquals(2, list.size());
        assertTrue(list.contains(h1));
        assertTrue(list.contains(h2));
    }

    @Test
    void getAfterAggregatorHandlers_returnsAdded() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        ChannelHandler h = mockHandler();
        customizer.addAfterAggregator(h);
        assertEquals(1, customizer.getAfterAggregatorHandlers().size());
        assertTrue(customizer.getAfterAggregatorHandlers().contains(h));
    }

    @Test
    void hasHandlers_true_whenAnyAdded() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        customizer.addAfterAggregator(mockHandler());
        assertTrue(customizer.hasHandlers());

        PipelineCustomizer customizer2 = new PipelineCustomizer();
        customizer2.addBeforeAggregator(mockHandler());
        assertTrue(customizer2.hasHandlers());
    }

    @Test
    void returnedLists_areUnmodifiable() {
        PipelineCustomizer customizer = new PipelineCustomizer();
        customizer.addBeforeAggregator(mockHandler());
        assertThrows(UnsupportedOperationException.class,
                () -> customizer.getBeforeAggregatorHandlers().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> customizer.getAfterAggregatorHandlers().add(mockHandler()));
    }

    private static ChannelHandler mockHandler() {
        return org.mockito.Mockito.mock(ChannelHandler.class);
    }
}
