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
}