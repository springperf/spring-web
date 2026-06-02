package io.springperf.web.http;

import java.util.concurrent.atomic.AtomicInteger;

public class RequestAttribute<T> {

    private static final AtomicInteger SEQ = new AtomicInteger();

    final int index;
    final Class<T> type;

    protected RequestAttribute(int index, Class<T> type) {
        this.index = index;
        this.type = type;
    }

    public static <T> RequestAttribute<T> createAttribute(Class<T> type) {
        return new RequestAttribute<>(SEQ.getAndIncrement(), type);
    }

    public static int getMaxSize() {
        return SEQ.get() + 1;
    }

    public int getIndex() {
        return index;
    }

    public Class<T> getType() {
        return type;
    }
}
