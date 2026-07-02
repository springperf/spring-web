package io.springperf.web.batch.common;

import io.springperf.web.core.mapping.PathMappingContext;

public class BatchHandlerRegistration {

    private final Object bean;
    private final PathMappingContext singleCtx;
    private final BatchRequestMetaData meta;

    public BatchHandlerRegistration(Object bean, PathMappingContext singleCtx, BatchRequestMetaData meta) {
        this.bean = bean;
        this.singleCtx = singleCtx;
        this.meta = meta;
    }

    public Object bean() { return bean; }
    public PathMappingContext singleCtx() { return singleCtx; }
    public BatchRequestMetaData meta() { return meta; }
}