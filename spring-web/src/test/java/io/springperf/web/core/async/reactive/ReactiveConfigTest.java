package io.springperf.web.core.async.reactive;

import io.springperf.web.core.async.stream.SseEmitter;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.*;

class ReactiveConfigTest {

    @Test
    void constructor_withStreamEmitterType() throws Exception {
        Constructor<SseEmitter> constructor = SseEmitter.class.getConstructor();
        ReactiveConfig config = new ReactiveConfig(SseEmitter.class, constructor, 100, 20, 5000L);

        assertEquals(SseEmitter.class, config.getStreamEmitterType());
        assertSame(constructor, config.getStreamEmitterConstructor());
        assertEquals(100, config.getHighWaterMark());
        assertEquals(20, config.getLowWaterMark());
        assertEquals(5000L, config.getTimeout());
    }

    @Test
    void constructor_withValues() {
        ReactiveConfig config = new ReactiveConfig(100, 20, 5000L);

        assertNull(config.getStreamEmitterType());
        assertNull(config.getStreamEmitterConstructor());
        assertEquals(100, config.getHighWaterMark());
        assertEquals(20, config.getLowWaterMark());
        assertEquals(5000L, config.getTimeout());
    }

    @Test
    void defaultConfig_values() {
        assertNull(ReactiveConfig.DEFAULT.getStreamEmitterType());
        assertNull(ReactiveConfig.DEFAULT.getStreamEmitterConstructor());
        assertEquals(150, ReactiveConfig.DEFAULT.getHighWaterMark());
        assertEquals(50, ReactiveConfig.DEFAULT.getLowWaterMark());
        assertEquals(-1, ReactiveConfig.DEFAULT.getTimeout());
    }
}