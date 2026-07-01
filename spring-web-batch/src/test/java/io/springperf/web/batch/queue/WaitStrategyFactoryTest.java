package io.springperf.web.batch.queue;

import com.lmax.disruptor.*;
import io.springperf.web.batch.annotation.BatchMapping;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WaitStrategyFactoryTest {

    @Test
    void blocking() {
        WaitStrategy strategy = WaitStrategyFactory.create(BatchMapping.WaitStrategy.BLOCKING);
        assertThat(strategy).isInstanceOf(BlockingWaitStrategy.class);
    }

    @Test
    void yielding() {
        WaitStrategy strategy = WaitStrategyFactory.create(BatchMapping.WaitStrategy.YIELDING);
        assertThat(strategy).isInstanceOf(YieldingWaitStrategy.class);
    }

    @Test
    void sleeping() {
        WaitStrategy strategy = WaitStrategyFactory.create(BatchMapping.WaitStrategy.SLEEPING);
        assertThat(strategy).isInstanceOf(SleepingWaitStrategy.class);
    }

    @Test
    void busySpin() {
        WaitStrategy strategy = WaitStrategyFactory.create(BatchMapping.WaitStrategy.BUSY_SPIN);
        assertThat(strategy).isInstanceOf(BusySpinWaitStrategy.class);
    }

    @Test
    void nullDefaultsToBlocking() {
        // The factory uses BLOCKING as default case
        WaitStrategy strategy = WaitStrategyFactory.create(null);
        assertThat(strategy).isInstanceOf(BlockingWaitStrategy.class);
    }
}
