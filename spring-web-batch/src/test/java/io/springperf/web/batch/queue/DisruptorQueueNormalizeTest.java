package io.springperf.web.batch.queue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DisruptorQueueNormalizeTest {

    @Test
    void zeroBecomes4096() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(0)).isEqualTo(4096);
    }

    @Test
    void negativeBecomes4096() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(-1)).isEqualTo(4096);
    }

    @Test
    void alreadyPowerOfTwo() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(1024)).isEqualTo(1024);
    }

    @Test
    void roundsUpToNextPowerOfTwo() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(1000)).isEqualTo(1024);
    }

    @Test
    void roundsUp2000To2048() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(2000)).isEqualTo(2048);
    }

    @Test
    void exact4096() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(4096)).isEqualTo(4096);
    }

    @Test
    void roundsUp4097To8192() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(4097)).isEqualTo(8192);
    }

    /* ==================== C8: 最小尺寸 64 + 溢出安全 ==================== */

    @Test
    void degenerateSizeOneBecomes64() {
        // 回归 C8：单槽 ring buffer 灾难，<64 必须提升到 64
        assertThat(DisruptorQueue.normalizeRingBufferSize(1)).isEqualTo(64);
    }

    @Test
    void size63Becomes64() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(63)).isEqualTo(64);
    }

    @Test
    void size64Stays64() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(64)).isEqualTo(64);
    }

    @Test
    void size100RoundsUpTo128() {
        assertThat(DisruptorQueue.normalizeRingBufferSize(100)).isEqualTo(128);
    }

    @Test
    void overflowSizeDoesNotSilentlyFallbackTo4096() {
        // 回归 C8+C12：Integer.MAX_VALUE 时 result <<= 1 溢出为负，
        // 必须回退到安全上限 2^18（构造器 warn 声明的最大合理容量），
        // 而非静默 4096、更非 2^30（2^30 槽位预分配 BatchEvent 必然 OOM）
        int result = DisruptorQueue.normalizeRingBufferSize(Integer.MAX_VALUE);
        assertThat(result).isEqualTo(1 << 18);
        assertThat(Integer.bitCount(result)).isEqualTo(1); // 是 2 的幂
    }

    @Test
    void sizeAtMaxPowerOfTwoFallsBackToSafeCeiling() {
        // 回归 C12：恰好 2^30 不触发左移溢出，但 2^30 槽位同样必然 OOM，
        // 必须钳制到安全上限 2^18（修复前返回 2^30）
        assertThat(DisruptorQueue.normalizeRingBufferSize(1 << 30)).isEqualTo(1 << 18);
    }

    @Test
    void largeButMemorySafePowerOfTwoClamped() {
        // 超过 2^18 的尺寸一律钳制到安全上限 2^18（262144 槽位，预分配约 6MB）
        assertThat(DisruptorQueue.normalizeRingBufferSize(1 << 20)).isEqualTo(1 << 18);
    }
}