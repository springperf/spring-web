package io.springperf.web.core.async.reactive;

import io.springperf.web.core.async.stream.StreamEmitter;
import lombok.Getter;

import java.lang.reflect.Constructor;

@Getter
public class ReactiveConfig {

    public static final ReactiveConfig DEFAULT;

    static {
        DEFAULT = new ReactiveConfig(150, 50, -1);
    }

    private final Class<? extends StreamEmitter> streamEmitterType;

    private final Constructor<? extends StreamEmitter> streamEmitterConstructor;

    private final int highWaterMark;

    private final int lowWaterMark;

    private final long timeout;

    public ReactiveConfig(int highWaterMark, int lowWaterMark, long timeout) {
        this.streamEmitterType = null;
        this.streamEmitterConstructor = null;
        this.highWaterMark = highWaterMark;
        this.lowWaterMark = lowWaterMark;
        this.timeout = timeout;
    }

    public ReactiveConfig(Class<? extends StreamEmitter> streamEmitterType, Constructor<? extends StreamEmitter> streamEmitterConstructor, int highWaterMark, int lowWaterMark, long timeout) {
        this.streamEmitterType = streamEmitterType;
        this.streamEmitterConstructor = streamEmitterConstructor;
        this.highWaterMark = highWaterMark;
        this.lowWaterMark = lowWaterMark;
        this.timeout = timeout;
    }
}
