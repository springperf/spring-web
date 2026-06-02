package io.springperf.web.core.mapping;

import java.util.concurrent.atomic.AtomicInteger;

public final class MappingCacheKey<T> {

    private static final AtomicInteger METHOD_CACHE_SEQ = new AtomicInteger();
    private static final AtomicInteger CLASS_CACHE_SEQ = new AtomicInteger();

    final int index;
    final Class<T> type;
    final boolean classCache;

    protected MappingCacheKey(int index, Class<T> type, boolean controllerCache) {
        this.index = index;
        this.type = type;
        this.classCache = controllerCache;
    }

    public static <T> MappingCacheKey<T> createMethodCacheKey(Class<T> type) {
        return new MappingCacheKey<>(METHOD_CACHE_SEQ.getAndIncrement(), type, false);
    }

    public static <T> MappingCacheKey<T> createClassCacheKey(Class<T> type) {
        return new MappingCacheKey<>(CLASS_CACHE_SEQ.getAndIncrement(), type, true);
    }
}
