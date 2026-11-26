package io.springperf.web.websocket.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;

/**
 * Flushes pending Netty ByteBuf leak reports at the end of this module's test run.
 *
 * <p>{@code ResourceLeakDetector} only writes a {@code LEAK: ByteBuf} line after a
 * weak reference has been collected <em>and</em> a later tracked allocation drains
 * the pending queue. Without an explicit GC plus allocation, the surefire JVM may
 * exit with reports still queued, turning the CI leak gate into a false negative.</p>
 *
 * <p>The {@code Zz} prefix and {@code runOrder=alphabetical} in {@code pom.xml}
 * must stay in place so this class runs last.</p>
 */
class ZzLeakFlushTest {

    @Test
    void flushPendingLeakReports() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100L);
            allocateAndReleaseProbe();
        }
    }

    // Must be a pooled buffer: Netty does not attach a leak tracker to unpooled heap
    // buffers (Unpooled.buffer()), so those allocations would not drain the queue.
    private static void allocateAndReleaseProbe() {
        ByteBuf probe = PooledByteBufAllocator.DEFAULT.buffer(16);
        probe.release();
    }
}
