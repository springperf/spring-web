package io.springperf.web.batch.queue;

import com.lmax.disruptor.*;
import io.springperf.web.batch.annotation.BatchMapping;

/**
 * {@link WaitStrategy} 工厂，根据 {@link BatchMapping.WaitStrategy} 枚举创建对应的 Disruptor 等待策略实例。
 *
 * @author <a href="https://github.com/springperf">SpringPerf</a>
 */
public final class WaitStrategyFactory {

    private WaitStrategyFactory() {
    }

    /**
     * 根据枚举值创建等待策略实例。
     *
     * @param strategy 等待策略枚举
     * @return Disruptor {@link WaitStrategy} 实例
     */
    public static WaitStrategy create(BatchMapping.WaitStrategy strategy) {
        if (strategy == null) {
            return new BlockingWaitStrategy();
        }
        switch (strategy) {
            case BLOCKING:
            default:
                return new BlockingWaitStrategy();
            case SLEEPING:
                return new SleepingWaitStrategy();
            case BUSY_SPIN:
                return new BusySpinWaitStrategy();
            case YIELDING:
                return new YieldingWaitStrategy();
        }
    }
}
