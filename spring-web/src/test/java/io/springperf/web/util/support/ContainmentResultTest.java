package io.springperf.web.util.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainmentResultTest {

    @Test
    void and_bothAlways_returnsAlways() {
        assertEquals(ContainmentResult.ALWAYS,
                ContainmentResult.and(ContainmentResult.ALWAYS, ContainmentResult.ALWAYS));
    }

    @Test
    void and_firstNever_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ContainmentResult.and(ContainmentResult.NEVER, ContainmentResult.ALWAYS));
        assertEquals(ContainmentResult.NEVER,
                ContainmentResult.and(ContainmentResult.NEVER, ContainmentResult.RUNTIME));
        assertEquals(ContainmentResult.NEVER,
                ContainmentResult.and(ContainmentResult.NEVER, ContainmentResult.NEVER));
    }

    @Test
    void and_secondNever_returnsNever() {
        assertEquals(ContainmentResult.NEVER,
                ContainmentResult.and(ContainmentResult.ALWAYS, ContainmentResult.NEVER));
        assertEquals(ContainmentResult.NEVER,
                ContainmentResult.and(ContainmentResult.RUNTIME, ContainmentResult.NEVER));
    }

    @Test
    void and_anyRuntime_returnsRuntime() {
        assertEquals(ContainmentResult.RUNTIME,
                ContainmentResult.and(ContainmentResult.ALWAYS, ContainmentResult.RUNTIME));
        assertEquals(ContainmentResult.RUNTIME,
                ContainmentResult.and(ContainmentResult.RUNTIME, ContainmentResult.ALWAYS));
        assertEquals(ContainmentResult.RUNTIME,
                ContainmentResult.and(ContainmentResult.RUNTIME, ContainmentResult.RUNTIME));
    }
}
