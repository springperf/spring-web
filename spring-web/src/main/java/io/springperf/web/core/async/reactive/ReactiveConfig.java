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
        this(null, null, highWaterMark, lowWaterMark, timeout);
    }

    public ReactiveConfig(Class<? extends StreamEmitter> streamEmitterType, Constructor<? extends StreamEmitter> streamEmitterConstructor, int highWaterMark, int lowWaterMark, long timeout) {
        // C14：背压水位 fail-fast 校验。lowWaterMark≤0 时 tryRequest 的补充请求永远不触发，
        // 遵守背压的 cold Publisher 在 highWaterMark 条后流停滞；highWaterMark≤lowWaterMark
        // 时背压区间为空（queueSize<low 才补充请求，而 high-low 无余量），逻辑自相矛盾。
        // 默认值 (150, 50) 合法。配置错误必须在构造期抛出而非运行期静默异常。
        if (lowWaterMark <= 0) {
            throw new IllegalArgumentException("lowWaterMark must be > 0, got " + lowWaterMark);
        }
        if (highWaterMark <= lowWaterMark) {
            throw new IllegalArgumentException("highWaterMark must be > lowWaterMark, got highWaterMark="
                    + highWaterMark + ", lowWaterMark=" + lowWaterMark);
        }
        this.streamEmitterType = streamEmitterType;
        this.streamEmitterConstructor = streamEmitterConstructor;
        this.highWaterMark = highWaterMark;
        this.lowWaterMark = lowWaterMark;
        this.timeout = timeout;
    }
}
