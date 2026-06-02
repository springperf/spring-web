package io.springperf.web.util.support;

public enum ContainmentResult {
    ALWAYS,
    NEVER,
    RUNTIME;

    public static ContainmentResult and(ContainmentResult a, ContainmentResult b) {
        if (a == NEVER || b == NEVER) return NEVER;
        if (a == RUNTIME || b == RUNTIME) return RUNTIME;
        return ALWAYS;
    }
}